package no.nav.budstikka.infrastructure.database.retention

import no.nav.budstikka.application.retention.RetentionConfig
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionPolicy
import no.nav.budstikka.application.retention.RetentionRepository
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
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
     * [RetentionPolicy.eligibleInboxStates], plus messages received before [anyStateCutoff]
     * regardless of state. The second cutoff lets stuck, unprocessed messages expire eventually;
     * those deletions are reported separately as `unprocessed`.
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
                    (InboxMessageTable.receivedAt less eligibleStateCutoff) and
                        (
                            (InboxMessageTable.state inList policy.eligibleInboxStates.toList()) or
                                (InboxMessageTable.receivedAt less anyStateCutoff)
                        )
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
    }
}
