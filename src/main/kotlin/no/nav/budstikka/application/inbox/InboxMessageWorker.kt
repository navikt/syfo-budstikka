package no.nav.budstikka.application.inbox

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.logging.ApplicationMdc
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.ClaimedInboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import java.util.UUID

/**
 * Claims hydrated inbox messages, decides them through [DecisionProcess], and persists each outcome
 * through [EffectuateDecision]. [LeaseBudgetDrainer] prevents a batch from starting work beyond its
 * lease budget.
 */
class InboxMessageWorker(
    private val repository: InboxMessageRepository,
    private val effectuator: EffectuateDecision,
    private val decisionProcess: DecisionProcess,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
    private val metrics: InboxMetrics,
) {
    private val logger = applicationLogger(InboxMessageWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.message.eventId.toString() },
            claim = {
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts).also { claimed ->
                    if (claimed.isEmpty()) metrics.emptyPoll() else metrics.claimed(claimed.size)
                }
            },
            process = { message -> processClaimed(message) },
        )
    }

    private suspend fun processClaimed(claimed: ClaimedInboxMessage) {
        val message = claimed.message
        val dispatch = Dispatch(reference = message.reference, content = message.content)
        ApplicationMdc.putCloseable(MdcKeys.REFERENCE, message.reference).use {
            withContext(MDCContext()) {
                if (!repository.beginAttempt(message.eventId, claimed.claimToken, config.maxAttempts)) {
                    // A peer reclaimed or terminated the row, or the poison gate owns its spent attempts.
                    logger.event(InboxLogEvents.claimSkipped)
                    return@withContext
                }
                completeDecision(message.eventId, claimed.claimToken, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        eventId: UUID,
        claimToken: UUID,
        decision: Decision,
    ) {
        if (!effectuator.effectuate(eventId, claimToken, decision)) {
            metrics.decisionCasLost()
            return
        }

        metrics.record(decision)
        logger.info("Inbox message processed", decision.logFields())
    }

    private fun InboxMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> processed()
            is Decision.Dropped -> dropped(decision.reason)
            is Decision.Failed -> failed()
            is Decision.NotInSendingWindow -> outsideSendingWindow(decision.reason)
        }
    }

    private fun Decision.logFields(): Map<String, Any> =
        when (this) {
            is Decision.Processed -> {
                mapOf(
                    MdcKeys.RESULT to "PROCESSED",
                    MdcKeys.DELIVERY_COUNT to deliveries.size,
                )
            }

            is Decision.Dropped -> {
                mapOf(
                    MdcKeys.RESULT to "DROPPED",
                    MdcKeys.REASON to reason.name,
                )
            }

            is Decision.Failed -> {
                mapOf(
                    MdcKeys.RESULT to "FAILED",
                    MdcKeys.REASON to errorMessage,
                )
            }

            is Decision.NotInSendingWindow ->
                mapOf(
                    MdcKeys.RESULT to "WAIT",
                    MdcKeys.REASON to reason,
                )
        }
}
