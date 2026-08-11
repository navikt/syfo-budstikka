package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import org.slf4j.LoggerFactory
import org.slf4j.MDC

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
    private val metrics: DispatchMetrics,
) {
    private val logger = LoggerFactory.getLogger(InboxMessageWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.eventId.toString() },
            claim = {
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts).also { claimed ->
                    if (claimed.isEmpty()) metrics.inboxEmptyPoll() else metrics.inboxClaimed(claimed.size)
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
                completeDecision(message, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        message: InboxMessage,
        decision: Decision,
    ) {
        val effectuation = effectuator.effectuate(message, decision)
        metrics.record(decision)
        when (effectuation) {
            EffectuationResult.FerdigstillWithoutMatch -> {
                metrics.ferdigstillWithoutMatch()
                logFerdigstillNoOp("Ferdigstill processed without matching create delivery")
                return
            }

            EffectuationResult.FerdigstillWithoutSupportedRuntimeChannel -> {
                metrics.ferdigstillWithoutSupportedRuntimeChannel()
                logFerdigstillNoOp("Ferdigstill processed without a supported runtime channel")
                return
            }

            EffectuationResult.FerdigstillWithInvalidStoredCreate -> {
                metrics.ferdigstillWithInvalidStoredCreate()
                logFerdigstillNoOp("Ferdigstill processed with an invalid stored create delivery")
                return
            }

            EffectuationResult.Completed,
            EffectuationResult.Skipped,
            is EffectuationResult.FerdigstillWithDelivery,
            -> Unit
        }
        val fields =
            decision.logFields(
                deliveryCount = (effectuation as? EffectuationResult.FerdigstillWithDelivery)?.deliveryCount,
            )
        logger.info(
            withPlaceholders("Inbox message processed", fields),
            *fields.toTypedArray(),
        )
    }

    /** FERDIGSTILL no-op logs carry only the low-cardinality outcome and the non-PII event MDC. */
    private fun logFerdigstillNoOp(message: String) {
        val reference = MDC.get(MdcKeys.REFERENCE)
        MDC.remove(MdcKeys.REFERENCE)
        try {
            logger.info(message)
        } finally {
            if (reference == null) {
                MDC.remove(MdcKeys.REFERENCE)
            } else {
                MDC.put(MdcKeys.REFERENCE, reference)
            }
        }
    }

    private fun DispatchMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> inboxProcessed()
            is Decision.Dropped -> inboxDropped(decision.reason)
            is Decision.Failed -> inboxFailed()
            is Decision.NotInSendingWindow -> inboxOutsideSendingWindow(decision.reason)
        }
    }

    private fun Decision.logFields(deliveryCount: Int? = null): List<StructuredArgument> =
        when (this) {
            is Decision.Processed -> {
                listOf(
                    kv(MdcKeys.RESULT, "PROCESSED"),
                    kv(MdcKeys.DELIVERY_COUNT, deliveryCount ?: deliveries.size),
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
