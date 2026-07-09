package no.nav.budstikka.infrastructure.database.formidling

import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.util.UUID
import kotlin.time.Clock

interface InboxFormidlingRepository {
    suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean
}

class InboxFormidlingRepositoryImpl(
    private val database: Database,
) : InboxFormidlingRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean =
        database.transact {
            val now = Clock.System.now()
            InboxFormidlingTable
                .insertIgnore {
                    it[InboxFormidlingTable.eventId] = eventId
                    it[InboxFormidlingTable.payload] = payload
                    it[InboxFormidlingTable.receivedAt] = now
                }.insertedCount > 0
        }
}
