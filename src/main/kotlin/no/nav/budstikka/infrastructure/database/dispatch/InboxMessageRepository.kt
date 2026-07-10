package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
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

    private companion object {
        const val RECEIVED_STATE = "RECEIVED"
    }
}
