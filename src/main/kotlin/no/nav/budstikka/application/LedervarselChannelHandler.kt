package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.domain.dispatch.Ledervarsel

/**
 * [ChannelHandler] for the LEDERVARSEL channel (ADR 0016): sends an in-app activity notification to
 * Dine Sykmeldte via [LedervarselPublisher]. Rethrows (transient) to the worker; a payload that does
 * not match the channel is a permanent [DeliveryOutcome.Failed]. Mirrors [BrukervarselChannelHandler].
 */
class LedervarselChannelHandler(
    private val publisher: LedervarselPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val ledervarsel =
            delivery.payload as? Ledervarsel
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match LEDERVARSEL channel: ${delivery.payload::class.simpleName}",
                )
        publisher.publish(delivery.reference, ledervarsel)
        return DeliveryOutcome.Sent
    }
}
