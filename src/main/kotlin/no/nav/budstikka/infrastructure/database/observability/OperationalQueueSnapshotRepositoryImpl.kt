package no.nav.budstikka.infrastructure.database.observability

import no.nav.budstikka.application.observability.DeliveryQueueKey
import no.nav.budstikka.application.observability.DeliveryQueueState
import no.nav.budstikka.application.observability.InboxQueueState
import no.nav.budstikka.application.observability.OperationalQueueSnapshot
import no.nav.budstikka.application.observability.OperationalQueueSnapshotRepository
import no.nav.budstikka.application.observability.QueueStats
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.Case
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.unionAll
import kotlin.time.Instant

class OperationalQueueSnapshotRepositoryImpl(
    private val database: Database,
) : OperationalQueueSnapshotRepository {
    override suspend fun snapshot(observedAt: Instant): OperationalQueueSnapshot =
        database.transact {
            TransactionManager.current().queryTimeout = QUERY_TIMEOUT_SECONDS
            val inboxQuery = inboxSnapshotQuery(observedAt)
            val inbox = mutableMapOf<InboxQueueState, QueueStats>()
            val deliveries = mutableMapOf<DeliveryQueueKey, QueueStats>()

            inboxQuery.query.unionAll(deliverySnapshotQuery(observedAt)).forEach { row ->
                val stats =
                    QueueStats(
                        size = row[inboxQuery.size],
                        oldestAt = row[inboxQuery.oldestAt],
                    )
                when (row[inboxQuery.queueType]) {
                    INBOX_QUEUE_TYPE -> {
                        val state = InboxQueueState.valueOf(row[inboxQuery.queueState])
                        check(inbox.put(state, stats) == null) { "Duplicate inbox queue state: $state" }
                    }

                    DELIVERY_QUEUE_TYPE -> {
                        val key =
                            DeliveryQueueKey(
                                channel = Channel.valueOf(row[inboxQuery.channel]),
                                state = DeliveryQueueState.valueOf(row[inboxQuery.queueState]),
                            )
                        check(deliveries.put(key, stats) == null) { "Duplicate delivery queue key: $key" }
                    }

                    else -> error("Unknown operational queue type")
                }
            }

            OperationalQueueSnapshot(
                observedAt = observedAt,
                inbox = inbox,
                deliveries = deliveries,
            )
        }

    private fun inboxSnapshotQuery(observedAt: Instant): SnapshotQuery {
        val expired =
            InboxMessageTable.nextAttemptTime.isNull() or
                (InboxMessageTable.nextAttemptTime lessEq observedAt)
        val due =
            (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                (
                    (InboxMessageTable.state inList listOf(InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)) and
                        expired
                )
        val queueState =
            Case()
                .When(due, stringLiteral(InboxQueueState.DUE.name))
                .When(
                    InboxMessageTable.state eq InboxMessageState.CLAIMED.name,
                    stringLiteral(InboxQueueState.IN_FLIGHT.name),
                ).Else(stringLiteral(InboxQueueState.WAITING.name))
                .alias(QUEUE_STATE_COLUMN)
        val oldestAt =
            Case()
                .When(InboxMessageTable.state eq InboxMessageState.RECEIVED.name, InboxMessageTable.receivedAt)
                .When(
                    (InboxMessageTable.state inList listOf(InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)) and
                        expired,
                    Coalesce<Instant, Instant?>(InboxMessageTable.nextAttemptTime, InboxMessageTable.receivedAt),
                ).Else(InboxMessageTable.receivedAt)
                .min()
                .alias(OLDEST_AT_COLUMN)
        val size = InboxMessageTable.eventId.count().alias(QUEUE_SIZE_COLUMN)
        val queueType = stringLiteral(INBOX_QUEUE_TYPE).alias(QUEUE_TYPE_COLUMN)
        val channel = stringLiteral("").alias(CHANNEL_COLUMN)
        val query =
            InboxMessageTable
                .select(queueType, channel, queueState, size, oldestAt)
                .where { InboxMessageTable.state inList INBOX_ACTIVE_STATES }
                .groupBy(queueState.aliasOnlyExpression())

        return SnapshotQuery(
            query = query,
            queueType = queueType,
            channel = channel,
            queueState = queueState,
            size = size,
            oldestAt = oldestAt,
        )
    }

    private fun deliverySnapshotQuery(observedAt: Instant): Query {
        val expired =
            DeliveryTable.nextAttemptTime.isNull() or
                (DeliveryTable.nextAttemptTime lessEq observedAt)
        val due =
            (DeliveryTable.state eq DeliveryState.READY.name) or
                ((DeliveryTable.state eq DeliveryState.CLAIMED.name) and expired)
        val queueState =
            Case()
                .When(due, stringLiteral(DeliveryQueueState.DUE.name))
                .Else(stringLiteral(DeliveryQueueState.IN_FLIGHT.name))
                .alias(QUEUE_STATE_COLUMN)
        val oldestAt =
            Case()
                .When(DeliveryTable.state eq DeliveryState.READY.name, DeliveryTable.createdAt)
                .When(
                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and expired,
                    Coalesce<Instant, Instant?>(DeliveryTable.nextAttemptTime, DeliveryTable.createdAt),
                ).Else(DeliveryTable.createdAt)
                .min()
                .alias(OLDEST_AT_COLUMN)
        val size = DeliveryTable.id.count().alias(QUEUE_SIZE_COLUMN)
        val queueType = stringLiteral(DELIVERY_QUEUE_TYPE).alias(QUEUE_TYPE_COLUMN)
        val channel = DeliveryTable.channel.alias(CHANNEL_COLUMN)

        return DeliveryTable
            .select(queueType, channel, queueState, size, oldestAt)
            .where { DeliveryTable.state inList DELIVERY_ACTIVE_STATES }
            .groupBy(channel.aliasOnlyExpression(), queueState.aliasOnlyExpression())
    }

    private data class SnapshotQuery(
        val query: Query,
        val queueType: Expression<String>,
        val channel: Expression<String>,
        val queueState: Expression<String>,
        val size: Expression<Long>,
        val oldestAt: Expression<Instant?>,
    )

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 5
        private const val INBOX_QUEUE_TYPE = "INBOX"
        private const val DELIVERY_QUEUE_TYPE = "DELIVERY"
        private const val QUEUE_TYPE_COLUMN = "queue_type"
        private const val CHANNEL_COLUMN = "channel"
        private const val QUEUE_STATE_COLUMN = "queue_state"
        private const val QUEUE_SIZE_COLUMN = "queue_size"
        private const val OLDEST_AT_COLUMN = "oldest_at"
        private val INBOX_ACTIVE_STATES =
            listOf(InboxMessageState.RECEIVED.name, InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)
        private val DELIVERY_ACTIVE_STATES = listOf(DeliveryState.READY.name, DeliveryState.CLAIMED.name)
    }
}
