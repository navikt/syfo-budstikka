package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import java.util.UUID

class ThrowingMessageRepository : InboxMessageRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = error("DB nede — transient feil")

    override suspend fun pollReceived(limit: Int): List<InboxMessage> = emptyList()

    override suspend fun markProcessed(eventId: UUID): Boolean = true

    override suspend fun markFailed(
        eventId: UUID,
        reason: String,
    ): Boolean = true
}
