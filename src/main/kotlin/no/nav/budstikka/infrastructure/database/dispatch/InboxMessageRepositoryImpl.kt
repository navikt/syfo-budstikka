package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.toKotlinDuration

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    override suspend fun saveBatch(events: List<Pair<UUID, String>>) {
        if (events.isEmpty()) {
            return
        }
        database.transact {
            val now = Clock.System.now()
            InboxMessageTable.batchInsert(events, ignore = true) { (eventId, payload) ->
                this[InboxMessageTable.eventId] = eventId
                this[InboxMessageTable.payload] = payload
                this[InboxMessageTable.receivedAt] = now
            }
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
    ): List<InboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        return database.transact {
            val now = Clock.System.now()
            val claimed =
                InboxMessageTable
                    .selectAll()
                    .where {
                        (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                            (
                                (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                                    (InboxMessageTable.nextAttemptTime lessEq now)
                            )
                    }.orderBy(
                        InboxMessageTable.receivedAt to SortOrder.ASC,
                        InboxMessageTable.eventId to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        InboxMessage(
                            eventId = row[InboxMessageTable.eventId],
                            payload = row[InboxMessageTable.payload],
                        )
                    }
            if (claimed.isNotEmpty()) {
                val leaseDeadline = now + lease.toKotlinDuration()
                InboxMessageTable.update({ InboxMessageTable.eventId inList claimed.map { it.eventId } }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[nextAttemptTime] = leaseDeadline
                    it[attempt] = attempt + 1
                }
            }
            claimed
        }
    }

    override fun markProcessedInTransaction(eventId: UUID): Boolean =
        terminate(eventId, state = InboxMessageState.PROCESSED, dropReason = null, errorMessage = null)

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.DROPPED, dropReason = reason, errorMessage = null)

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.FAILED, dropReason = null, errorMessage = reason)

    private fun terminate(
        eventId: UUID,
        state: InboxMessageState,
        dropReason: String?,
        errorMessage: String?,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq eventId) and (InboxMessageTable.state eq InboxMessageState.CLAIMED.name)
        }) {
            it[InboxMessageTable.state] = state.name
            it[InboxMessageTable.dropReason] = dropReason
            it[InboxMessageTable.errorMessage] = errorMessage
            it[processedAt] = Clock.System.now()
        } > 0
}
