package no.nav.budstikka.application.delivery

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.budstikka.application.logging.ApplicationMdc
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.domain.decision.Channel

class DeliveryWorker(
    private val repository: DeliveryRepository,
    private val handlers: Map<Channel, ChannelHandler>,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
    private val metrics: DeliveryMetrics,
) {
    private val logger = applicationLogger(DeliveryWorker::class.java)

    suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.inboxEventId?.toString() ?: it.id.toString() },
            failureFields = { it.failureFields() },
            claim = {
                repository
                    .claim(config.batchSize, config.leaseDuration, config.maxAttempts, handlers.keys)
                    .also { claimed ->
                        if (claimed.isEmpty()) metrics.emptyPoll() else metrics.claimed(claimed.size)
                    }
            },
            process = { dispatch(it) },
        )
    }

    private fun ClaimedDelivery.failureFields() =
        mapOf(
            MdcKeys.DELIVERY_ID to id.toString(),
            MdcKeys.DELIVERY_CHANNEL to channel.toString(),
            MdcKeys.REFERENCE to reference,
            MdcKeys.HANDLER to (handlers[channel]?.javaClass?.simpleName ?: "missing"),
        )

    private fun ClaimedDelivery.logFields(): Map<String, Any> =
        mapOf(
            MdcKeys.EVENT_ID to (inboxEventId ?: id).toString(),
            MdcKeys.DELIVERY_ID to id.toString(),
            MdcKeys.REFERENCE to reference,
        )

    private suspend fun dispatch(delivery: ClaimedDelivery) {
        // Keep delivery fields on MDC through suspend points during dispatch.
        ApplicationMdc.putCloseable(MdcKeys.DELIVERY_CHANNEL, delivery.channel.toString()).use {
            ApplicationMdc.putCloseable(MdcKeys.REFERENCE, delivery.reference).use {
                withContext(MDCContext()) {
                    dispatchToHandler(delivery)
                }
            }
        }
    }

    private suspend fun dispatchToHandler(delivery: ClaimedDelivery) {
        val handler = handlers[delivery.channel]
        if (handler == null) {
            // Leave row CLAIMED for lease reclaim instead of forcing terminal failure. A missing
            // handler is a configuration error, not poison data, so it must not spend an attempt.
            logger.event(DeliveryLogEvents.handlerMissing)
            return
        }
        if (!repository.beginAttempt(delivery.id, config.maxAttempts)) {
            // A peer terminated the row, or its attempts are spent and the poison gate owns it.
            logger.event(DeliveryLogEvents.claimSkipped)
            return
        }
        when (val outcome = handler.handle(delivery)) {
            DeliveryOutcome.Sent -> markSent(delivery)
            is DeliveryOutcome.Failed -> markFailed(delivery, outcome.reason)
        }
    }

    private suspend fun markSent(delivery: ClaimedDelivery) {
        if (repository.markSent(delivery.id)) {
            metrics.sent(delivery.channel)
            logger.info("Delivery sent successfully", delivery.logFields())
        } else {
            logger.event(DeliveryLogEvents.sentTransitionFailed)
        }
    }

    private suspend fun markFailed(
        delivery: ClaimedDelivery,
        reason: String,
    ) {
        if (repository.markFailed(delivery.id, reason)) {
            metrics.failed(delivery.channel)
            logger.event(
                DeliveryLogEvents.markedFailed,
                DeliveryFailureContext(
                    eventId = (delivery.inboxEventId ?: delivery.id).toString(),
                    deliveryId = delivery.id.toString(),
                    reference = delivery.reference,
                    reason = reason,
                ),
            )
        } else {
            logger.event(DeliveryLogEvents.failedTransitionFailed)
        }
    }
}
