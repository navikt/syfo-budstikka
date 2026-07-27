package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Outbox worker claims `delivery` rows for channels with a [ChannelHandler] (FOR UPDATE SKIP LOCKED
 * plus lease, ADR 0004: several replicas concurrently) and dispatches every row to the appropriate
 * handler. The worker depends only on [handlers], not concrete publishers, so a new channel is one
 * handler plus registration.
 */
class DeliveryWorker(
    private val repository: DeliveryRepository,
    private val handlers: Map<Channel, ChannelHandler>,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
    private val metrics: DispatchMetrics,
) {
    private val logger = LoggerFactory.getLogger(DeliveryWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.inboxEventId?.toString() ?: it.id.toString() },
            failureFields = { it.failureFields() },
            claim = {
                repository
                    .claim(config.batchSize, config.leaseDuration, config.maxAttempts, handlers.keys)
                    .also { claimed ->
                        if (claimed.isEmpty()) metrics.deliveryEmptyPoll() else metrics.deliveryClaimed(claimed.size)
                    }
            },
            process = { dispatch(it) },
        )
    }

    private fun ClaimedDelivery.failureFields() =
        listOf(
            kv(MdcKeys.DELIVERY_ID, id.toString()),
            kv(MdcKeys.DELIVERY_CHANNEL, channel.toString()),
            kv(MdcKeys.REFERENCE, reference),
            kv(MdcKeys.HANDLER, handlers[channel]?.javaClass?.simpleName ?: "missing"),
        )

    private fun ClaimedDelivery.logFields(): List<StructuredArgument> =
        listOf(
            kv(MdcKeys.EVENT_ID, (inboxEventId ?: id).toString()),
            kv(MdcKeys.DELIVERY_ID, id.toString()),
            kv(MdcKeys.REFERENCE, reference),
        )

    private suspend fun dispatch(delivery: ClaimedDelivery) {
        // Keep delivery fields on MDC through suspend points during dispatch.
        MDC.putCloseable(MdcKeys.DELIVERY_CHANNEL, delivery.channel.toString()).use {
            MDC.putCloseable(MdcKeys.REFERENCE, delivery.reference).use {
                withContext(MDCContext()) {
                    dispatchToHandler(delivery)
                }
            }
        }
    }

    private suspend fun dispatchToHandler(delivery: ClaimedDelivery) {
        val handler = handlers[delivery.channel]
        if (handler == null) {
            // Leave row CLAIMED for lease reclaim instead of forcing terminal failure.
            logger.error("No handler for claimed channel; leaving row for lease reclaim")
            return
        }
        when (val outcome = handler.handle(delivery)) {
            DeliveryOutcome.Sent -> markSent(delivery)
            is DeliveryOutcome.Failed -> markFailed(delivery, outcome.reason)
        }
    }

    private suspend fun markSent(delivery: ClaimedDelivery) {
        if (repository.markSent(delivery.id)) {
            metrics.deliverySent(delivery.channel)
            val fields = delivery.logFields()
            logger.info(withPlaceholders("Delivery sent successfully", fields), *fields.toTypedArray())
        } else {
            logger.warn("Could not mark delivery as SENT because row is no longer CLAIMED")
        }
    }

    private suspend fun markFailed(
        delivery: ClaimedDelivery,
        reason: String,
    ) {
        if (repository.markFailed(delivery.id, reason)) {
            metrics.deliveryFailed(delivery.channel)
            val fields = delivery.logFields() + kv(MdcKeys.REASON, reason)
            logger.warn(withPlaceholders("Marked delivery as FAILED", fields), *fields.toTypedArray())
        } else {
            logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
        }
    }
}
