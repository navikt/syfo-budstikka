package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import java.util.UUID

class FakeInboxMessageRepository(
    private val polledMessages: List<InboxMessage> = emptyList(),
) : InboxMessageRepository {
    // Pair: (payload, eventId.toString)
    val savedEvents = mutableListOf<Pair<String, String>>()
    val pollLimits = mutableListOf<Int>()

    override suspend fun saveBatch(events: List<Pair<UUID, String>>) {
        events.forEach { (eventId, payload) ->
            savedEvents += payload to eventId.toString()
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: java.time.Duration,
    ): List<InboxMessage> {
        pollLimits += limit
        return polledMessages
    }

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
