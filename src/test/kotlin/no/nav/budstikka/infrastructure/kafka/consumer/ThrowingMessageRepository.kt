package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import java.util.UUID
import kotlin.time.Duration

class ThrowingMessageRepository : InboxMessageRepository {
    override suspend fun saveBatch(messages: List<InboxMessage>) = error("DB nede — transient feil")

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
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
