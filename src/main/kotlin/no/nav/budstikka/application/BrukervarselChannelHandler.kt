package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.MinSideBrukervarselPublisher
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.Brukervarsel

class BrukervarselChannelHandler(
    private val publisher: MinSideBrukervarselPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val brukervarsel =
            delivery.payload as? Brukervarsel
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match BRUKERVARSEL channel: ${delivery.payload::class.simpleName}",
                )
        withChannelHandlerFailureContext(Channel.BRUKERVARSEL, "publishing brukervarsel") {
            publisher.publish(delivery.reference, brukervarsel)
        }
        return DeliveryOutcome.Sent
    }
}
