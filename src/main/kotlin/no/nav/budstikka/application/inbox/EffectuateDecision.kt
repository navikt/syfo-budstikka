package no.nav.budstikka.application.inbox

import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.InboxClaim
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.OrderedCommand
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.OrderingKey
import no.nav.budstikka.domain.decision.ScheduledDispatch
import no.nav.budstikka.domain.decision.orderingKey
import no.nav.budstikka.domain.decision.scheduledDispatch
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Persists one [Decision] atomically: delivery rows and inbox state commit or roll back together.
 * External lookups must finish before this transaction begins.
 *
 * Decisions for the same [OrderingKey] are persisted one at a time. A newer command in ingest order
 * cancels pending [ScheduledDispatch]es from earlier commands, and a decision's own scheduled dispatch
 * is written as a waiting inbox row unless a later command for the key has already been decided.
 */
class EffectuateDecision(
    private val transactionRunner: TransactionRunner,
    private val inboxMessageRepository: InboxMessageRepository,
    private val deliveryRepository: DeliveryRepository,
) {
    suspend fun effectuate(
        claim: InboxClaim,
        decision: Decision,
    ): EffectuationResult =
        transactionRunner.transaction {
            when (decision) {
                is Decision.Processed -> {
                    val key = decision.orderingKey()
                    if (key == null) {
                        saveProcessed(claim, decision.deliveries)
                    } else {
                        saveProcessedForKey(claim, key, decision)
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

    private fun saveProcessed(
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

    private fun saveProcessedForKey(
        claim: InboxClaim,
        key: OrderingKey,
        decision: Decision.Processed,
    ): EffectuationResult {
        val command =
            inboxMessageRepository.lockOrderedCommandInTransaction(claim.eventId, key)
                ?: return EffectuationResult.Skipped
        val result = saveProcessed(claim, decision.deliveries)
        if (result == EffectuationResult.Skipped) {
            return result
        }
        return replaceScheduledDispatch(claim.eventId, command, decision.scheduledDispatch())
    }

    private fun replaceScheduledDispatch(
        sourceEventId: UUID,
        command: OrderedCommand,
        scheduled: ScheduledDispatch?,
    ): EffectuationResult {
        inboxMessageRepository.cancelPendingInTransaction(command.earlierScheduled)
        if (scheduled == null || command.laterCommandDecided) {
            return EffectuationResult.Completed
        }
        return saveScheduledDispatch(sourceEventId, command.receivedAt, scheduled)
    }

    private fun saveScheduledDispatch(
        sourceEventId: UUID,
        sourceReceivedAt: Instant,
        scheduled: ScheduledDispatch,
    ): EffectuationResult {
        val latestWake = sourceReceivedAt + SCHEDULING_HORIZON
        inboxMessageRepository.saveDerivedInTransaction(
            sourceEventId = sourceEventId,
            message = InboxMessage(scheduledEventId(sourceEventId), scheduled.reference, scheduled.content),
            waitReason = scheduled.waitReason,
            notBefore = minOf(scheduled.notBefore, latestWake),
        )
        return if (scheduled.notBefore > latestWake) {
            EffectuationResult.CompletedWithCappedSchedule
        } else {
            EffectuationResult.Completed
        }
    }

    private fun Boolean.toResult(): EffectuationResult = if (this) EffectuationResult.Completed else EffectuationResult.Skipped
}

/** Outcome of [EffectuateDecision.effectuate]. */
sealed interface EffectuationResult {
    /** This worker won the state transition and committed the decision. */
    data object Completed : EffectuationResult

    /**
     * Completed, but the scheduled dispatch lay beyond [SCHEDULING_HORIZON] after the command was
     * received, so it was scheduled at the horizon instead.
     */
    data object CompletedWithCappedSchedule : EffectuationResult

    /** The row left CLAIMED or a peer reclaimed it, so nothing was written. */
    data object Skipped : EffectuationResult
}

/**
 * Latest a scheduled dispatch may wake, measured from the source command's `received_at`. Retention
 * deletes every inbox row 365 days after `received_at`, whatever its state, so a row scheduled later
 * would be deleted before it wakes. 30 days of margin leave room for a stopped worker to catch up.
 * The producer facade applies the same limit to `visibleUntil`.
 */
internal val SCHEDULING_HORIZON = 335.days

/**
 * Event id of the dispatch scheduled from [sourceEventId]. Deterministic, so a repeated decision
 * finds the row it already wrote instead of scheduling twice. A changed suffix would let a repeated
 * decision schedule a second row, so keep it.
 */
internal fun scheduledEventId(sourceEventId: UUID): UUID =
    UUID.nameUUIDFromBytes((sourceEventId.toString() + "scheduled-disable").toByteArray())
