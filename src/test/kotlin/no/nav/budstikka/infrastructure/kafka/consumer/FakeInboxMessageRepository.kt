package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import java.util.UUID

class FakeInboxMessageRepository(
    private val shouldReturnNewRowCreated: Boolean = true,
    private val polledMessages: List<InboxMessage> = emptyList(),
) : InboxMessageRepository {
    // Pair: (payload, eventId.toString)
    val savedEvents = mutableListOf<Pair<String, String>>()
    val pollLimits = mutableListOf<Int>()

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean {
        savedEvents += payload to eventId.toString()
        return shouldReturnNewRowCreated
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
