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
                completeDecision(message, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        message: InboxMessage,
        decision: Decision,
    ) {
        val effectuation = effectuator.effectuate(message, decision)
        if (effectuation == EffectuationResult.Skipped) {
            metrics.decisionCasLost()
            return
        }

        metrics.record(decision)
        when (effectuation) {
            EffectuationResult.FerdigstillWithoutMatch -> {
                metrics.ferdigstillWithoutMatch()
                logPiiFreeFerdigstillOutcome("Ferdigstill processed without matching create delivery")
                return
            }

            EffectuationResult.FerdigstillWithoutSupportedRuntimeChannel -> {
                metrics.ferdigstillWithoutSupportedRuntimeChannel()
                logPiiFreeFerdigstillOutcome("Ferdigstill processed without a supported runtime channel")
                return
            }

            is EffectuationResult.FerdigstillWithInvalidStoredCreate -> {
                metrics.ferdigstillWithInvalidStoredCreate()
                metrics.recordCancelledCreates(effectuation.cancelledCreateCount)
                logPiiFreeFerdigstillOutcome(
                    "Ferdigstill processed with an invalid stored create delivery",
                    effectuation.cancellationFields(),
                )
                return
            }

            is EffectuationResult.FerdigstillWithDelivery -> {
                if (effectuation.invalidStoredCreateCount > 0) {
                    metrics.ferdigstillWithInvalidStoredCreate()
                }
                metrics.recordCancelledCreates(effectuation.cancelledCreateCount)
            }

            is EffectuationResult.FerdigstillWithCancellation -> {
                metrics.recordCancelledCreates(effectuation.cancelledCreateCount)
                logPiiFreeFerdigstillOutcome(
                    "Ferdigstill cancelled unmaterialized create inbox rows",
                    listOf(
                        kv(MdcKeys.RESULT, "FERDIGSTILL_CREATE_CANCELLED"),
                        kv(MdcKeys.CANCELLED_CREATE_COUNT, effectuation.cancelledCreateCount),
                    ),
                )
                return
            }

            EffectuationResult.Completed,
            EffectuationResult.Skipped,
            -> Unit
        }
        val fields =
            decision.logFields(
                deliveryCount = (effectuation as? EffectuationResult.FerdigstillWithDelivery)?.deliveryCount,
                cancelledCreateCount =
                    (effectuation as? EffectuationResult.FerdigstillWithDelivery)?.cancelledCreateCount,
            )
        logger.info(
            withPlaceholders("Inbox message processed", fields),
            *fields.toTypedArray(),
        )
    }

    /** FERDIGSTILL outcome logs carry only PII-free fields and the non-PII event MDC. */
    private fun logPiiFreeFerdigstillOutcome(
        message: String,
        fields: List<StructuredArgument> = emptyList(),
    ) {
        val reference = MDC.get(MdcKeys.REFERENCE)
        MDC.remove(MdcKeys.REFERENCE)
        try {
            logger.info(withPlaceholders(message, fields), *fields.toTypedArray())
        } finally {
            if (reference == null) {
                MDC.remove(MdcKeys.REFERENCE)
            } else {
                MDC.put(MdcKeys.REFERENCE, reference)
            }
        }
    }

    private fun InboxMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> processed()
            is Decision.Dropped -> dropped(decision.reason)
            is Decision.Failed -> failed()
            is Decision.NotInSendingWindow -> outsideSendingWindow(decision.reason)
        }
    }

    private fun InboxMetrics.recordCancelledCreates(count: Int) {
        if (count > 0) {
            ferdigstillCancelledCreates(count)
        }
    }

    private fun EffectuationResult.FerdigstillWithInvalidStoredCreate.cancellationFields(): List<StructuredArgument> =
        if (cancelledCreateCount > 0) {
            listOf(kv(MdcKeys.CANCELLED_CREATE_COUNT, cancelledCreateCount))
        } else {
            emptyList()
        }

    private fun Decision.logFields(
        deliveryCount: Int? = null,
        cancelledCreateCount: Int? = null,
    ): List<StructuredArgument> =
        when (this) {
            is Decision.Processed -> {
                listOf(
                    kv(MdcKeys.RESULT, "PROCESSED"),
                    kv(MdcKeys.DELIVERY_COUNT, deliveryCount ?: deliveries.size),
                ) +
                    cancelledCreateCount
                        ?.takeIf { it > 0 }
                        ?.let { listOf(kv(MdcKeys.CANCELLED_CREATE_COUNT, it)) }
                        .orEmpty()
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
