package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.dispatch.Microfrontend

class MicrofrontendChannelHandler(
    private val publisher: MicrofrontendPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val microfrontend =
            delivery.payload as? Microfrontend
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match MICROFRONTEND channel: ${delivery.payload::class.simpleName}",
                )
        withChannelHandlerFailureContext(Channel.MICROFRONTEND, "publishing microfrontend") {
            publisher.publish(microfrontend)
        }
        return DeliveryOutcome.Sent
    }
}
