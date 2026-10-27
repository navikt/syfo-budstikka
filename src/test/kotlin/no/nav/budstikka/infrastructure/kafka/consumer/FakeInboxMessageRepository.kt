package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedInboxMessage
import no.nav.budstikka.application.port.InboxClaim
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendCommand
import no.nav.budstikka.application.port.MicrofrontendKey
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
    ): List<ClaimedInboxMessage> {
        pollLimits += limit
        val claimToken = ClaimToken.generate()
        return polledMessages.map { ClaimedInboxMessage(it, claimToken) }
    }

    override suspend fun beginAttempt(
        claim: InboxClaim,
        maxAttempts: Int,
    ): Boolean = true

    override fun markProcessedInTransaction(claim: InboxClaim): Boolean = true

    override fun markDroppedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean = true

    override fun markFailedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean = true

    override fun markOutsideSendingWindowInTransaction(
        claim: InboxClaim,
        reason: String,
        nextRetry: Instant,
    ): Boolean = true

    override fun lockMicrofrontendCommandInTransaction(
        eventId: UUID,
        key: MicrofrontendKey,
    ): MicrofrontendCommand =
        MicrofrontendCommand(Instant.DISTANT_PAST, earlierScheduledDisables = emptyList(), laterCommandDecided = false)

    override fun cancelPendingInTransaction(eventIds: List<UUID>): Int = eventIds.size

    override fun saveDerivedInTransaction(
        sourceEventId: UUID,
        message: InboxMessage,
        waitReason: String,
        notBefore: Instant,
    ): Boolean = true

    fun calls(): List<String> = calls
}
