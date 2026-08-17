package no.nav.budstikka.infrastructure.database.retention

import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionRepository
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class RetentionRepositoryImpl(
    private val database: Database,
    private val clock: Clock = Clock.System,
) : RetentionRepository {
    override suspend fun run(batchSize: Int): RetentionResult {
        require(batchSize in 1..MAXIMUM_BATCH_SIZE) {
            "batchSize must be between 1 and $MAXIMUM_BATCH_SIZE"
        }
        return database.transact {
            val connection = TransactionManager.current().connection.connection as Connection
            if (!connection.tryAcquireCleanupLock()) {
                return@transact RetentionResult.SkippedDueToLockContention
            }
            val now = clock.now()
            RetentionResult.Completed(
                RetentionCounts(
                    inboxMessages = deleteOldInboxMessages(now - INBOX_AND_DEAD_LETTER_RETENTION, batchSize),
                    deadLetterMessages =
                        deleteOldDeadLetterMessages(now - INBOX_AND_DEAD_LETTER_RETENTION, batchSize),
                    deliveries = deleteOldTerminalDeliveries(now - DELIVERY_RETENTION, batchSize),
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

    private fun deleteOldInboxMessages(
        cutoff: kotlin.time.Instant,
        batchSize: Int,
    ): Int {
        val candidateIds =
            InboxMessageTable
                .select(InboxMessageTable.eventId)
                .where { InboxMessageTable.receivedAt less cutoff }
                .orderBy(InboxMessageTable.receivedAt to SortOrder.ASC, InboxMessageTable.eventId to SortOrder.ASC)
                .limit(batchSize)
                .map { it[InboxMessageTable.eventId] }

        return InboxMessageTable.deleteWhere { InboxMessageTable.eventId inList candidateIds }
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
                        (DeliveryTable.state inList listOf(DeliveryState.SENT.name, DeliveryState.FAILED.name))
                }.orderBy(DeliveryTable.createdAt to SortOrder.ASC, DeliveryTable.id to SortOrder.ASC)
                .limit(batchSize)
                .map { it[DeliveryTable.id] }

        return DeliveryTable.deleteWhere { DeliveryTable.id inList candidateIds }
    }

    companion object {
        const val MAXIMUM_BATCH_SIZE = 100
        private val INBOX_AND_DEAD_LETTER_RETENTION = 100.days
        private val DELIVERY_RETENTION = 180.days

        internal const val RETENTION_CLEANUP_LOCK_NAMESPACE = 0x42554453
        internal const val RETENTION_CLEANUP_LOCK_KEY = 0x5245544e

        private const val TRY_ADVISORY_LOCK = "SELECT pg_try_advisory_xact_lock(?, ?)"
    }
}
