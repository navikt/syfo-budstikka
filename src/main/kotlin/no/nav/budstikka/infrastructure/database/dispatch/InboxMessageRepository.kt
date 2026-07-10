package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
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

    suspend fun markProcessed(eventId: UUID): Boolean

    suspend fun markFailed(
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

    override suspend fun markProcessed(eventId: UUID): Boolean =
        database.transact {
            InboxMessageTable.update({
                InboxMessageTable.eventId eq eventId
            }) {
                it[state] = PROCESSED_STATE
                it[processedAt] = Clock.System.now()
                it[errorMessage] = null
            } > 0
        }

    override suspend fun markFailed(
        eventId: UUID,
        reason: String,
    ): Boolean =
        database.transact {
            InboxMessageTable.update({
                InboxMessageTable.eventId eq eventId
            }) {
                it[state] = FAILED_STATE
                it[processedAt] = Clock.System.now()
                it[errorMessage] = reason
            } > 0
        }

    private companion object {
        const val RECEIVED_STATE = "RECEIVED"
        const val PROCESSED_STATE = "PROCESSED"
        const val FAILED_STATE = "FAILED"
    }
}
