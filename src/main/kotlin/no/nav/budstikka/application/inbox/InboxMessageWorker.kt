package no.nav.budstikka.application.inbox

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.withPlaceholders
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import org.slf4j.LoggerFactory
import org.slf4j.MDC
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
    private val logger = LoggerFactory.getLogger(InboxMessageWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.eventId.toString() },
            claim = {
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts).also { claimed ->
                    if (claimed.isEmpty()) metrics.emptyPoll() else metrics.claimed(claimed.size)
                }
            },
            process = { message -> processClaimed(message) },
        )
    }

    private suspend fun processClaimed(message: InboxMessage) {
        val dispatch = Dispatch(reference = message.reference, content = message.content)
        MDC.putCloseable(MdcKeys.REFERENCE, message.reference).use {
            withContext(MDCContext()) {
                if (!repository.beginAttempt(message.eventId, config.maxAttempts)) {
                    // A peer terminated the row, or its attempts are spent and the poison gate owns it.
                    logger.warn("Skipping inbox message because the row is no longer claimable or has spent its attempts")
                    return@withContext
                }
                completeDecision(message.eventId, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        eventId: UUID,
        decision: Decision,
    ) {
        if (!effectuator.effectuate(eventId, decision)) {
            metrics.decisionCasLost()
            return
        }

        metrics.record(decision)
        val fields = decision.logFields()
        logger.info(
            withPlaceholders("Inbox message processed", fields),
            *fields.toTypedArray(),
        )
    }

    private fun InboxMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> processed()
            is Decision.Dropped -> dropped(decision.reason)
            is Decision.Failed -> failed()
            is Decision.NotInSendingWindow -> outsideSendingWindow(decision.reason)
        }
    }

    private fun Decision.logFields(): List<StructuredArgument> =
        when (this) {
            is Decision.Processed -> {
                listOf(
                    kv(MdcKeys.RESULT, "PROCESSED"),
                    kv(MdcKeys.DELIVERY_COUNT, deliveries.size),
                )
            }

            is Decision.Dropped -> {
                listOf(
                    kv(MdcKeys.RESULT, "DROPPED"),
                    kv(MdcKeys.REASON, reason.name),
                )
            }

            is Decision.Failed -> {
                listOf(
                    kv(MdcKeys.RESULT, "FAILED"),
                    kv(MdcKeys.REASON, errorMessage),
                )
            }

            is Decision.NotInSendingWindow ->
                listOf(
                    kv(MdcKeys.RESULT, "WAIT"),
                    kv(MdcKeys.REASON, reason),
                )
        }
}
