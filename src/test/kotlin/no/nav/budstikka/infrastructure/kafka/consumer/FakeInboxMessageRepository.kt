package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import java.util.UUID

class FakeInboxMessageRepository(
    private val shouldReturnNewRowCreated: Boolean = true,
) : InboxMessageRepository {
    // Pair: (payload, eventId.toString)
    val savedEvents = mutableListOf<Pair<String, String>>()

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean {
        savedEvents += payload to eventId.toString()
        return shouldReturnNewRowCreated
    }
}
