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
 * Outbox-workeren claimer `delivery`-rader for de kanalene den har en [ChannelHandler] for
 * (FOR UPDATE SKIP LOCKED + lease, ADR 0004 — flere replicaer samtidig), og dispatcher hver rad til
 * riktig handler. Workeren avhenger kun av [handlers] — ikke av konkrete publishers — så en
 * ny kanal er én handler + registrering.
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
                repository.claim(config.batchSize, config.leaseDuration, config.maxAttempts, handlers.keys).also { claimed ->
                    if (claimed.isEmpty()) metrics.deliveryEmptyPoll() else metrics.deliveryClaimed(claimed.size)
                }
            },
            process = { dispatch(it) },
        )
    }

    private fun ClaimedDelivery.failureFields() =
        listOf(
            kv("deliveryId", id.toString()),
            kv("deliveryChannel", channel.toString()),
            kv("reference", reference),
            kv("handler", handlers[channel]?.javaClass?.simpleName ?: "missing"),
        )

    private fun ClaimedDelivery.logFields(): List<StructuredArgument> =
        listOf(
            kv("eventId", (inboxEventId ?: id).toString()),
            kv("deliveryId", id.toString()),
            kv("reference", reference),
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
            logger.info("Delivery sent successfully".withPlaceholders(fields), *fields.toTypedArray())
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
            val fields = delivery.logFields() + kv("reason", reason)
            logger.warn("Marked delivery as FAILED".withPlaceholders(fields), *fields.toTypedArray())
        } else {
            logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
        }
    }

    private fun String.withPlaceholders(fields: List<StructuredArgument>): String = this + " {}".repeat(fields.size)
}
