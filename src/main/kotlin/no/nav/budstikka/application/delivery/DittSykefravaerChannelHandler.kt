package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.DittSykefravaer

class DittSykefravaerChannelHandler(
    private val publisher: DittSykefravaerPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val dittSykefravaer =
            delivery.payload as? DittSykefravaer
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match DITT_SYKEFRAVAER channel: ${delivery.payload::class.simpleName}",
                )
        publisher.publish(delivery.reference, dittSykefravaer)
        return DeliveryOutcome.Sent
    }
}
