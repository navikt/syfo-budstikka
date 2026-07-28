package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.dispatch.Dispatch
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Decision worker (#56): claims received inbox messages (FOR UPDATE SKIP LOCKED + lease, ADR 0004:
 * several replicas can run concurrently) and effectuates each result through [EffectuateDecision]
 * (delivery plus inbox status in one database transaction).
 *
 * The message is hydrated at ingest (ADR 0008): `content` is guaranteed parseable, so the worker
 * does not decode the payload. It reconstructs [Dispatch] from the row and delegates the decision to
 * [DecisionProcess].
 *
 * The worker owns one round ([runOnce]); bootstrap composes loop lifecycle (interval, heartbeat,
 * shutdown) around it through `BackgroundLoop`. It shares lease-budget draining with the outbox
 * worker through [LeaseBudgetDrainer]: it stops starting messages once the lease budget is spent, so
 * a slow batch does not cross the lease (and a peer re-enriches the same message). Untouched claimed
 * messages remain until their lease expires.
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
                completeDecision(message.eventId, decisionProcess.process(dispatch))
            }
        }
    }

    private suspend fun completeDecision(
        eventId: UUID,
        decision: Decision,
    ) {
        effectuator.effectuate(eventId, decision)
        metrics.record(decision)
        val fields = decision.logFields()
        logger.info(
            withPlaceholders("Inbox message processed", fields),
            *fields.toTypedArray(),
        )
    }

    private fun DispatchMetrics.record(decision: Decision) {
        when (decision) {
            is Decision.Processed -> inboxProcessed()
            is Decision.Dropped -> inboxDropped(decision.reason)
            is Decision.Failed -> inboxFailed()
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
        }
}
