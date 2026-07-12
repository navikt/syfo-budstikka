package no.nav.budstikka.application

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.delivery.ClaimedDelivery
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepository
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.LeaseBudgetDrainer
import no.nav.budstikka.infrastructure.task.config.LeaseDrainConfig
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
class DeliveryTask(
    private val repository: DeliveryRepository,
    private val handlers: Map<Channel, ChannelHandler>,
    private val drainer: LeaseBudgetDrainer,
    private val config: LeaseDrainConfig,
) : BaseTask(
        name = "delivery-task",
        interval = config.interval,
    ) {
    private val logger = LoggerFactory.getLogger(DeliveryTask::class.java)

    override suspend fun runIteration() {
        runOnce()
    }

    internal suspend fun runOnce() {
        drainer.drain(
            leaseDuration = config.leaseDuration,
            eventId = { it.inboxEventId?.toString() ?: it.id.toString() },
            claim = { repository.claim(config.batchSize, config.leaseDuration, handlers.keys) },
            process = { dispatch(it) },
        )
    }

    private suspend fun dispatch(delivery: ClaimedDelivery) {
        MDC.putCloseable(MdcKeys.DELIVERY_CHANNEL, delivery.channel.toString()).use {
            val handler = handlers[delivery.channel]
            if (handler == null) {
                // Kan ikke skje: claim filtrerer på handlers.keys. En rad uten handler er en
                // wiring-feil — la den stå CLAIMED (lease-reclaim), ikke terminal-feil den.
                logger.error("No handler for claimed channel {}; leaving row for lease reclaim", delivery.channel)
                return
            }
            when (val outcome = handler.handle(delivery)) {
                DeliveryOutcome.Sent -> markSent(delivery)
                is DeliveryOutcome.Failed -> markFailed(delivery, outcome.reason)
            }
        }
    }

    private suspend fun markSent(delivery: ClaimedDelivery) {
        if (repository.markSent(delivery.id)) {
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
            logger.warn("Marked delivery as FAILED: {}", reason)
        } else {
            logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
        }
    }
}
