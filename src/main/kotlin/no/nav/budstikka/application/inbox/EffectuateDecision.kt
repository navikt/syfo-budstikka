package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxClaim
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendKey
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.contract.Microfrontend
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins.
 *
 * A processed microfrontend command also carries out `visibleUntil` (ADR 0018). The last command per
 * [MicrofrontendKey] in ingest order wins: it cancels pending scheduled disables from earlier commands,
 * and an enable with `visibleUntil` schedules a disable as a derived inbox row unless a later command
 * for the key has already been decided. A `visibleUntil` that has passed when the decision is persisted
 * turns the enable into an immediate disable.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
    private val clock: Clock = Clock.System,
) {
    suspend fun effectuate(
        claim: InboxClaim,
        decision: Decision,
    ): EffectuationResult =
        transactionRunner.transaction {
            when (decision) {
                is Decision.Processed -> {
                    val now = clock.now()
                    val deliveries = decision.deliveries.map { it.disabledWhenVisibilityPassed(now) }
                    val key = deliveries.microfrontendKey()
                    if (key == null) {
                        processed(claim, deliveries)
                    } else {
                        processedMicrofrontend(claim, key, deliveries)
                    }
                }

                is Decision.Dropped ->
                    inboxMessageRepository.markDroppedInTransaction(claim, decision.reason.name).toResult()

                is Decision.Failed ->
                    inboxMessageRepository.markFailedInTransaction(claim, decision.errorMessage).toResult()

                is Decision.NotInSendingWindow ->
                    inboxMessageRepository
                        .markOutsideSendingWindowInTransaction(
                            claim,
                            decision.reason,
                            decision.nextRetry,
                        ).toResult()
            }
        }

    private fun processed(
        claim: InboxClaim,
        deliveries: List<DeliveryDraft>,
    ): EffectuationResult {
        // Only the worker winning CLAIMED->PROCESSED writes delivery rows.
        if (!inboxMessageRepository.markProcessedInTransaction(claim)) {
            return EffectuationResult.Skipped
        }
        deliveryRepository.saveInTransaction(claim.eventId, deliveries)
        return EffectuationResult.Completed
    }

    private fun processedMicrofrontend(
        claim: InboxClaim,
        key: MicrofrontendKey,
        deliveries: List<DeliveryDraft>,
    ): EffectuationResult {
        val command =
            inboxMessageRepository.lockMicrofrontendCommandInTransaction(claim.eventId, key)
                ?: return EffectuationResult.Skipped
        val result = processed(claim, deliveries)
        if (result == EffectuationResult.Skipped) {
            return result
        }
        inboxMessageRepository.cancelPendingInTransaction(command.earlierScheduledDisables)

        val enableDraft = deliveries.firstOrNull { it.content is MicrofrontendEnable } ?: return result
        val enable = enableDraft.content as MicrofrontendEnable
        val visibleUntil = enable.visibleUntil
        if (visibleUntil == null || command.laterCommandDecided) {
            return result
        }
        val horizon = command.receivedAt + SCHEDULED_DISABLE_HORIZON
        inboxMessageRepository.saveDerivedInTransaction(
            sourceEventId = claim.eventId,
            message =
                InboxMessage(
                    eventId = scheduledDisableEventId(claim.eventId),
                    reference = enableDraft.reference,
                    content = MicrofrontendDisable(enable.personIdentifier, enable.microfrontendId),
                ),
            waitReason = SCHEDULED_DISABLE_WAIT_REASON,
            notBefore = minOf(visibleUntil, horizon),
        )
        return if (visibleUntil > horizon) EffectuationResult.CompletedWithCappedScheduledDisable else result
    }

    private fun Boolean.toResult(): EffectuationResult = if (this) EffectuationResult.Completed else EffectuationResult.Skipped
}

/** Outcome of [EffectuateDecision.effectuate]. */
sealed interface EffectuationResult {
    /** This worker won the state transition and committed the decision. */
    data object Completed : EffectuationResult

    /**
     * Completed, but `visibleUntil` lay beyond [SCHEDULED_DISABLE_HORIZON] after the command was
     * received, so the disable was scheduled at the horizon instead.
     */
    data object CompletedWithCappedScheduledDisable : EffectuationResult

    /** The row left CLAIMED or a peer reclaimed it, so nothing was written. */
    data object Skipped : EffectuationResult
}

/** Wait reason of a scheduled disable derived from a microfrontend enable with `visibleUntil`. */
internal const val SCHEDULED_DISABLE_WAIT_REASON = "SCHEDULED_DISABLE"

/**
 * Latest a scheduled disable may fire, measured from the source command's `received_at`. Retention
 * deletes every inbox row 365 days after `received_at`, whatever its state, so a disable scheduled
 * later would be deleted before it fires. 30 days of margin leave room for a stopped worker to catch
 * up. The producer facade applies the same limit.
 */
internal val SCHEDULED_DISABLE_HORIZON = 335.days

/**
 * Event id of the disable scheduled from the enable [sourceEventId]. Deterministic, so a repeated
 * decision finds the row it already wrote instead of scheduling a second disable.
 */
internal fun scheduledDisableEventId(sourceEventId: UUID): UUID =
    UUID.nameUUIDFromBytes((sourceEventId.toString() + "scheduled-disable").toByteArray())

private fun List<DeliveryDraft>.microfrontendKey(): MicrofrontendKey? {
    val keys =
        mapNotNull { draft ->
            (draft.content as? Microfrontend)?.let { MicrofrontendKey(it.personIdentifier, it.microfrontendId) }
        }.toSet()
    check(keys.size <= 1) { "A microfrontend decision must address a single microfrontend key" }
    return keys.singleOrNull()
}

/** A `visibleUntil` that has already passed makes the enable an immediate disable. */
private fun DeliveryDraft.disabledWhenVisibilityPassed(now: Instant): DeliveryDraft {
    val enable = content as? MicrofrontendEnable ?: return this
    val visibleUntil = enable.visibleUntil ?: return this
    if (visibleUntil > now) {
        return this
    }
    return copy(
        operation = Operation.INACTIVATE,
        content = MicrofrontendDisable(enable.personIdentifier, enable.microfrontendId),
    )
}
