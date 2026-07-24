package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.LedervarselPublisher
import no.nav.budstikka.domain.dispatch.Ledervarsel

/**
 * [ChannelHandler] for LEDERVARSEL-kanalen (B62): sender in-app aktivitetsvarsel til Dine Sykmeldte
 * via [LedervarselPublisher]. Kaster (transient) videre til workeren; en payload som ikke matcher
 * kanalen er en permanent [DeliveryOutcome.Failed]. Speiler [BrukervarselChannelHandler].
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
