package no.nav.budstikka.application

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.Microfrontend
import no.nav.budstikka.infrastructure.config.MdcKeys
import no.nav.budstikka.infrastructure.database.delivery.ClaimedDelivery
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepository
import no.nav.budstikka.infrastructure.kafka.producer.MicrofrontendPublisher
import no.nav.budstikka.infrastructure.task.BaseTask
import no.nav.budstikka.infrastructure.task.config.DeliveryTaskConfig
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

class DeliveryTask(
    private val repository: DeliveryRepository,
    private val microfrontendPublisher: MicrofrontendPublisher,
    private val config: DeliveryTaskConfig,
) : BaseTask(
        name = TASK_NAME,
        interval = config.interval,
    ) {
    private val logger = LoggerFactory.getLogger(DeliveryTask::class.java)

    override suspend fun runIteration() {
        runOnce()
    }

    internal suspend fun runOnce() {
        val startedAt = Clock.System.now()
        val budget = (config.leaseDuration.toMillis() * config.leaseBudgetFraction).toLong().milliseconds
        val claimed = repository.claim(config.batchSize, config.leaseDuration, supportedChannels())
        for ((index, delivery) in claimed.withIndex()) {
            if (Clock.System.now() - startedAt >= budget) {
                logger.warn(
                    "Stopping delivery batch drain at {}% of lease budget with {} of {} claimed row(s) unprocessed",
                    (config.leaseBudgetFraction * 100).toInt(),
                    claimed.size - index,
                    claimed.size,
                )
                break
            }
            if (delivery.inboxEventId == null) {
                publish(delivery)
            } else {
                MDC.putCloseable(MdcKeys.EVENT_ID, delivery.inboxEventId.toString()).use {
                    publish(delivery)
                }
            }
        }
    }

    private suspend fun publish(delivery: ClaimedDelivery) {
        when (delivery.channel) {
            Channel.MICROFRONTEND -> publishToMicrofrontend(delivery)
            else -> {
                val reason = "No publisher configured for channel ${delivery.channel}"
                val markedFailed = repository.markFailed(delivery.id, reason)
                if (markedFailed) {
                    logger.warn("Marked delivery as FAILED: {}", reason)
                } else {
                    logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
                }
            }
        }
    }

    private suspend fun publishToMicrofrontend(delivery: ClaimedDelivery) {
        val microfrontend =
            delivery.payload as? Microfrontend ?: run {
                val reason = "Payload does not match MICROFRONTEND channel: ${delivery.payload::class.simpleName}"
                val markedFailed = repository.markFailed(delivery.id, reason)
                if (markedFailed) {
                    logger.warn("Marked delivery as FAILED: {}", reason)
                } else {
                    logger.warn("Could not mark delivery as FAILED because row is no longer CLAIMED")
                }
                return
            }
        microfrontendPublisher.publish(microfrontend)
        if (repository.markSent(delivery.id)) {
            logger.info("Delivery sent successfully")
        } else {
            logger.warn("Could not mark delivery as SENT because row is no longer CLAIMED")
        }
    }

    private fun supportedChannels(): Set<Channel> = setOf(Channel.MICROFRONTEND)

    private companion object {
        const val TASK_NAME = "delivery-task"
    }
}
