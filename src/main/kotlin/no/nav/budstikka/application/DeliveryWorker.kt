package no.nav.budstikka.application

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Outbox-workeren (B27): claimer `delivery`-rader for de kanalene den har en [ChannelHandler] for
 * (FOR UPDATE SKIP LOCKED + lease, ADR 0004 — flere replicaer samtidig), og dispatcher hver rad til
 * riktig handler. Workeren avhenger kun av sømmen [handlers] — ikke av konkrete publishers — så en
 * ny kanal er én handler + registrering.
 *
 * Statusovergangen bor her (ikke i handlerne): [DeliveryOutcome.Sent] → SENT,
 * [DeliveryOutcome.Failed] → FAILED (permanent). Transient feil = handleren kaster; raden blir
 * stående CLAIMED og plukkes opp når leasen utløper. Lease-budsjett-draineringen deles med
 * inbox-workeren via [LeaseBudgetDrainer].
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

    private suspend fun dispatch(delivery: ClaimedDelivery) {
        // B45: `delivery_channel` + `reference` på MDC for hele send-steget, så `| reference="X"`
        // korrelerer OPPRETT- og FERDIGSTILL-leveransen (ulik delivery, delt reference).
        // `withContext(MDCContext())` re-snapshotter MDC (eventId fra draineren + disse) inn i
        // coroutine-konteksten så feltene overlever handler-suspensjon (I/O) fram til send-loggen.
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
            // Kan ikke skje: claim filtrerer på handlers.keys. En rad uten handler er en
            // wiring-feil — la den stå CLAIMED (lease-reclaim), ikke terminal-feil den.
            // `delivery_channel` bæres alt på MDC (se dispatch) → ikke dupliser som kv-felt.
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
            logger.info("Delivery sent successfully")
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
            logger.warn("Marked delivery as FAILED {}", kv("reason", reason))
        } else {
            logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
        }
    }
}
