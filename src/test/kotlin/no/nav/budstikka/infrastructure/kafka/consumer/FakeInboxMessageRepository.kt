package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.decision.FerdigstillMatch
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

class FakeInboxMessageRepository(
    private val polledMessages: List<InboxMessage> = emptyList(),
    private val calls: MutableList<String> = mutableListOf(),
) : InboxMessageRepository {
    val savedEvents = mutableListOf<InboxMessage>()
    val pollLimits = mutableListOf<Int>()

    override suspend fun saveBatch(messages: List<InboxMessage>) {
        calls += "save"
        savedEvents += messages
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage> {
        pollLimits += limit
        return polledMessages
    }

    override suspend fun beginAttempt(
        eventId: UUID,
        maxAttempts: Int,
    ): Boolean = true

    override fun markProcessedInTransaction(eventId: UUID): Boolean = true

    override fun lockClaimedForEffectuationInTransaction(eventId: UUID): Boolean = true

    override fun lockWaitingCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID> = emptyList()

    override fun markWaitingCreateProcessedInTransaction(eventId: UUID): Boolean = true

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = true

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = true

    override fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean = true

    fun calls(): List<String> = calls
}
