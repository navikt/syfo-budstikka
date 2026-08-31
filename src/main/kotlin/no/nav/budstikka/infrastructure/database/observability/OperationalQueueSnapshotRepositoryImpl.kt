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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import kotlin.time.Instant

class OperationalQueueSnapshotRepositoryImpl(
    private val database: Database,
) : OperationalQueueSnapshotRepository {
    override suspend fun snapshot(observedAt: Instant): OperationalQueueSnapshot =
        database.transact {
            TransactionManager.current().queryTimeout = QUERY_TIMEOUT_SECONDS
            OperationalQueueSnapshot(
                observedAt = observedAt,
                inbox = inboxSnapshot(observedAt),
                deliveries = deliverySnapshot(observedAt),
            )
        }

    private fun inboxSnapshot(observedAt: Instant): Map<InboxQueueState, QueueStats> {
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
                .alias("queue_state")
        val oldestAt =
            Case()
                .When(InboxMessageTable.state eq InboxMessageState.RECEIVED.name, InboxMessageTable.receivedAt)
                .When(
                    (InboxMessageTable.state inList listOf(InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)) and
                        expired,
                    Coalesce<Instant, Instant?>(InboxMessageTable.nextAttemptTime, InboxMessageTable.receivedAt),
                ).Else(InboxMessageTable.receivedAt)
                .min()
        val size = InboxMessageTable.eventId.count()

        return InboxMessageTable
            .select(queueState, size, oldestAt)
            .where { InboxMessageTable.state inList INBOX_ACTIVE_STATES }
            .groupBy(queueState.aliasOnlyExpression())
            .associate { row ->
                InboxQueueState.valueOf(row[queueState]) to
                    QueueStats(
                        size = row[size],
                        oldestAt = row[oldestAt],
                    )
            }
    }

    private fun deliverySnapshot(observedAt: Instant): Map<DeliveryQueueKey, QueueStats> {
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
                .alias("queue_state")
        val oldestAt =
            Case()
                .When(DeliveryTable.state eq DeliveryState.READY.name, DeliveryTable.createdAt)
                .When(
                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and expired,
                    Coalesce<Instant, Instant?>(DeliveryTable.nextAttemptTime, DeliveryTable.createdAt),
                ).Else(DeliveryTable.createdAt)
                .min()
        val size = DeliveryTable.id.count()

        return DeliveryTable
            .select(DeliveryTable.channel, queueState, size, oldestAt)
            .where { DeliveryTable.state inList DELIVERY_ACTIVE_STATES }
            .groupBy(DeliveryTable.channel, queueState.aliasOnlyExpression())
            .associate { row ->
                DeliveryQueueKey(
                    channel = Channel.valueOf(row[DeliveryTable.channel]),
                    state = DeliveryQueueState.valueOf(row[queueState]),
                ) to
                    QueueStats(
                        size = row[size],
                        oldestAt = row[oldestAt],
                    )
            }
    }

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 5
        private val INBOX_ACTIVE_STATES =
            listOf(InboxMessageState.RECEIVED.name, InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)
        private val DELIVERY_ACTIVE_STATES = listOf(DeliveryState.READY.name, DeliveryState.CLAIMED.name)
    }
}
