package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.ClaimedInboxMessage
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

class ThrowingMessageRepository : InboxMessageRepository {
    override suspend fun saveBatch(messages: List<InboxMessage>) = error("Database unavailable — transient failure")

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<ClaimedInboxMessage> = emptyList()

    override suspend fun beginAttempt(
        eventId: UUID,
        claimToken: UUID,
        maxAttempts: Int,
    ): Boolean = true

    override fun markProcessedInTransaction(
        eventId: UUID,
        claimToken: UUID,
    ): Boolean = true

    override fun markDroppedInTransaction(
        eventId: UUID,
        claimToken: UUID,
        reason: String,
    ): Boolean = true

    override fun markFailedInTransaction(
        eventId: UUID,
        claimToken: UUID,
        reason: String,
    ): Boolean = true

    override fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        claimToken: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean = true
}
