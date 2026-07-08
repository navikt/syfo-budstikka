package no.nav.budstikka.infrastructure.kafka.formidling

import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepository
import java.util.UUID

class FakeInboxFormidlingRepository(
    private val skalReturnereNyRad: Boolean = true,
) : InboxFormidlingRepository {
    // Pair: (payload, eventId.toString)
    val savedEvents = mutableListOf<Pair<String, String>>()

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean {
        savedEvents += payload to eventId.toString()
        return skalReturnereNyRad
    }
}
