package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import java.util.UUID

class ThrowingMessageRepository : InboxMessageRepository {
    override suspend fun saveBatch(events: List<Pair<UUID, String>>): Int = error("DB nede — transient feil")

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = saveBatch(listOf(eventId to payload)) > 0

    override suspend fun claim(
        limit: Int,
        lease: java.time.Duration,
    ): List<InboxMessage> = emptyList()

    override fun markProcessedInTransaction(eventId: UUID): Boolean = true

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = true

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = true
}
