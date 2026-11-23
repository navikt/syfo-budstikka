package no.nav.budstikka.infrastructure.database.retention

import no.nav.budstikka.application.retention.RetentionConfig
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionPolicy
import no.nav.budstikka.application.retention.RetentionRepository
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.textField
import org.jetbrains.exposed.v1.core.Alias
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.notExists
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import kotlin.time.Clock

class PostgresRetentionRepository(
    private val database: Database,
    private val policy: RetentionPolicy,
    private val clock: Clock = Clock.System,
) : RetentionRepository {
    override suspend fun run(batchSize: Int): RetentionResult {
        require(batchSize in 1..RetentionConfig.MAXIMUM_BATCH_SIZE) {
            "batchSize must be between 1 and ${RetentionConfig.MAXIMUM_BATCH_SIZE}"
        }
        return database.transact {
            val connection = TransactionManager.current().connection.connection as Connection
            if (!connection.tryAcquireCleanupLock()) {
                return@transact RetentionResult.SkippedDueToLockContention
            }
            val now = clock.now()
            val inboxCounts =
                deleteOldInboxMessages(
                    now - policy.inboxAndDeadLetterRetention,
                    now - policy.inboxAbsoluteRetention,
                    batchSize,
                )
            RetentionResult.Completed(
                RetentionCounts(
                    inboxMessages = inboxCounts.total,
                    deadLetterMessages =
                        deleteOldDeadLetterMessages(now - policy.inboxAndDeadLetterRetention, batchSize),
                    deliveries = deleteOldTerminalDeliveries(now - policy.deliveryRetention, batchSize),
                    unprocessedInboxMessages = inboxCounts.unprocessed,
                ),
            )
        }
    }

    private fun Connection.tryAcquireCleanupLock(): Boolean =
        prepareStatement(TRY_ADVISORY_LOCK).use { statement ->
            statement.setInt(1, RETENTION_CLEANUP_LOCK_NAMESPACE)
            statement.setInt(2, RETENTION_CLEANUP_LOCK_KEY)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "PostgreSQL advisory lock query returned no result" }
                resultSet.getBoolean(1)
            }
        }

    /**
     * Deletes inbox messages received before [eligibleStateCutoff] whose state is in
     * [RetentionPolicy.eligibleInboxStates] and that no waiting delivery, pending schedule or older
     * enable still needs, plus messages received before [anyStateCutoff] regardless of state. The
     * second cutoff lets stuck, unprocessed messages expire eventually; those deletions are reported
     * separately as `unprocessed`.
     */
    private fun deleteOldInboxMessages(
        eligibleStateCutoff: kotlin.time.Instant,
        anyStateCutoff: kotlin.time.Instant,
        batchSize: Int,
    ): InboxDeletionCounts {
        val candidates =
            InboxMessageTable
                .select(InboxMessageTable.eventId, InboxMessageTable.state)
                .where {
                    val old = InboxMessageTable.receivedAt less eligibleStateCutoff
                    val finishedAndUnneeded =
                        (InboxMessageTable.state inList policy.eligibleInboxStates.toList()) and
                            noPendingDelivery() and
                            noPendingScheduled() and
                            not(preventsStaleDisable())
                    val pastAbsoluteCutoff = InboxMessageTable.receivedAt less anyStateCutoff
                    old and (finishedAndUnneeded or pastAbsoluteCutoff)
                }.orderBy(InboxMessageTable.receivedAt to SortOrder.ASC, InboxMessageTable.eventId to SortOrder.ASC)
                .limit(batchSize)
                .forUpdate()
                .map { it[InboxMessageTable.eventId] to it[InboxMessageTable.state] }

        val deleted = InboxMessageTable.deleteWhere { InboxMessageTable.eventId inList candidates.map { it.first } }
        check(deleted == candidates.size) { "Inbox retention deletion count differs from selected candidates" }
        return InboxDeletionCounts(
            total = deleted,
            unprocessed = candidates.count { (_, state) -> state !in policy.eligibleInboxStates },
        )
    }

    /**
     * No delivery from this inbox row is still waiting to be sent. The row is kept until then, so a
     * retry keeps the inbox event id as its correlation id and as the notification's identity.
     */
    private fun noPendingDelivery(): Op<Boolean> =
        notExists(
            DeliveryTable
                .select(DeliveryTable.id)
                .where {
                    (DeliveryTable.inboxEventId eq InboxMessageTable.eventId) and
                        (DeliveryTable.state inList PENDING_DELIVERY_STATES)
                },
        )

    /**
     * No scheduled row derived from this inbox row still waits. The source is kept until then, so a
     * producer retry with the same event id is still a duplicate and cannot cancel the plan.
     */
    private fun noPendingScheduled(): Op<Boolean> {
        val scheduled = InboxMessageTable.alias("scheduled")
        return notExists(
            scheduled
                .select(scheduled[InboxMessageTable.eventId])
                .where {
                    (scheduled[InboxMessageTable.sourceEventId] eq InboxMessageTable.eventId) and
                        (scheduled[InboxMessageTable.state] inList InboxMessageState.WAITING_OR_CLAIMED)
                },
        )
    }

    /**
     * A processed command must stay while an older pending enable can still schedule a disable for
     * the same target. Otherwise the enable would later schedule a disable that this newer command
     * already replaced.
     */
    private fun preventsStaleDisable(): Op<Boolean> {
        val enable = InboxMessageTable.alias("pending_enable")
        val olderPendingEnable =
            enable.select(enable[InboxMessageTable.eventId]).where {
                canScheduleDisable(enable) and sameTarget(enable) and arrivedEarlier(enable)
            }
        return isProcessedCommand() and exists(olderPendingEnable)
    }

    private fun isProcessedCommand(): Op<Boolean> =
        with(InboxMessageTable) {
            (state eq InboxMessageState.PROCESSED.name) and sourceEventId.isNull()
        }

    private fun canScheduleDisable(enable: Alias<InboxMessageTable>): Op<Boolean> =
        with(InboxMessageTable) {
            enable[sourceEventId].isNull() and
                (enable[state] inList InboxMessageState.PENDING) and
                (enable[content].textField("type") eq "MicrofrontendEnable") and
                enable[content].textField("visibleUntil").isNotNull()
        }

    private fun sameTarget(enable: Alias<InboxMessageTable>): Op<Boolean> =
        with(InboxMessageTable) {
            (enable[content].textField("personIdentifier") eq content.textField("personIdentifier")) and
                (enable[content].textField("microfrontendId") eq content.textField("microfrontendId"))
        }

    private fun arrivedEarlier(enable: Alias<InboxMessageTable>): Op<Boolean> =
        with(InboxMessageTable) {
            (enable[receivedAt] less receivedAt) or
                ((enable[receivedAt] eq receivedAt) and (enable[eventId] less eventId))
        }

    private fun deleteOldDeadLetterMessages(
        cutoff: kotlin.time.Instant,
        batchSize: Int,
    ): Int {
        val candidateIds =
            DeadLetterMessageTable
                .select(DeadLetterMessageTable.id)
                .where { DeadLetterMessageTable.receivedAt less cutoff }
                .orderBy(DeadLetterMessageTable.receivedAt to SortOrder.ASC, DeadLetterMessageTable.id to SortOrder.ASC)
                .limit(batchSize)
                .map { it[DeadLetterMessageTable.id] }

        return DeadLetterMessageTable.deleteWhere { DeadLetterMessageTable.id inList candidateIds }
    }

    private fun deleteOldTerminalDeliveries(
        cutoff: kotlin.time.Instant,
        batchSize: Int,
    ): Int {
        val candidateIds =
            DeliveryTable
                .select(DeliveryTable.id)
                .where {
                    (DeliveryTable.createdAt less cutoff) and
                        (DeliveryTable.state inList policy.eligibleDeliveryStates.toList())
                }.orderBy(DeliveryTable.createdAt to SortOrder.ASC, DeliveryTable.id to SortOrder.ASC)
                .limit(batchSize)
                .map { it[DeliveryTable.id] }

        return DeliveryTable.deleteWhere { DeliveryTable.id inList candidateIds }
    }

    private data class InboxDeletionCounts(
        val total: Int,
        val unprocessed: Int,
    )

    companion object {
        internal const val RETENTION_CLEANUP_LOCK_NAMESPACE = 0x42554453
        internal const val RETENTION_CLEANUP_LOCK_KEY = 0x5245544e

        private const val TRY_ADVISORY_LOCK = "SELECT pg_try_advisory_xact_lock(?, ?)"

        private val PENDING_DELIVERY_STATES = listOf(DeliveryState.READY.name, DeliveryState.CLAIMED.name)
    }
}
