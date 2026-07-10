package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.util.UUID
import kotlin.time.Clock

interface InboxMessageRepository {
    suspend fun save(
        eventId: UUID,
        payload: String,
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
}
