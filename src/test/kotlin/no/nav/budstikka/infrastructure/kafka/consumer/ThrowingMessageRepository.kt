package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import java.util.UUID

class ThrowingMessageRepository : InboxMessageRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = error("DB nede — transient feil")
}
