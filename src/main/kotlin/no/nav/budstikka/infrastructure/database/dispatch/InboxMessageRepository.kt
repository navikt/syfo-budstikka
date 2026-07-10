package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock

data class InboxMessage(
    val eventId: UUID,
    val payload: String,
)

interface InboxMessageRepository {
    suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean

    suspend fun pollReceived(limit: Int): List<InboxMessage>

    /**
     * Terminal-overgangene for beslutnings-workeren. De åpner IKKE egen transaksjon — de kjøres
     * inne i [no.nav.budstikka.infrastructure.database.config.TransactionRunner.transaction], sammen
     * med delivery-skrivingen, slik at én melding effektueres alt-eller-ingenting (#56). Overgangen
     * gjelder kun fra RECEIVED (idempotent: en allerede terminert melding gir `false`).
     */
    fun markProcessedInTransaction(eventId: UUID): Boolean

    fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean

    fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean
}

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean =
        database.transact {
            val now = Clock.System.now()
            InboxMessageTable
                .insertIgnore {
                    it[InboxMessageTable.eventId] = eventId
                    it[InboxMessageTable.payload] = payload
                    it[InboxMessageTable.receivedAt] = now
                }.insertedCount > 0
        }

    override suspend fun pollReceived(limit: Int): List<InboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        return database.transact {
            InboxMessageTable
                .selectAll()
                .where { InboxMessageTable.state eq RECEIVED_STATE }
                .orderBy(
                    InboxMessageTable.receivedAt to SortOrder.ASC,
                    InboxMessageTable.eventId to SortOrder.ASC,
                ).limit(limit)
                .map { row ->
                    InboxMessage(
                        eventId = row[InboxMessageTable.eventId],
                        payload = row[InboxMessageTable.payload],
                    )
                }
        }
    }

    override fun markProcessedInTransaction(eventId: UUID): Boolean =
        terminate(eventId, state = PROCESSED_STATE, dropReason = null, errorMessage = null)

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = DROPPED_STATE, dropReason = reason, errorMessage = null)

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = FAILED_STATE, dropReason = null, errorMessage = reason)

    private fun terminate(
        eventId: UUID,
        state: String,
        dropReason: String?,
        errorMessage: String?,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq eventId) and (InboxMessageTable.state eq RECEIVED_STATE)
        }) {
            it[InboxMessageTable.state] = state
            it[InboxMessageTable.dropReason] = dropReason
            it[InboxMessageTable.errorMessage] = errorMessage
            it[processedAt] = Clock.System.now()
        } > 0

    private companion object {
        const val RECEIVED_STATE = "RECEIVED"
        const val PROCESSED_STATE = "PROCESSED"
        const val DROPPED_STATE = "DROPPED"
        const val FAILED_STATE = "FAILED"
    }
}
