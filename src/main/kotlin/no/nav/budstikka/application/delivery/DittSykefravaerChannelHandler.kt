package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.DittSykefravaer
import no.nav.budstikka.contract.DittSykefravaerCreate

class DittSykefravaerChannelHandler(
    private val publisher: DittSykefravaerPublisher,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val dittSykefravaer =
            delivery.payload as? DittSykefravaer
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match DITT_SYKEFRAVAER channel: ${delivery.payload::class.simpleName}",
                )
        if (dittSykefravaer is DittSykefravaerCreate && dittSykefravaer.meldingType == null) {
            return DeliveryOutcome.Failed("DITT_SYKEFRAVAER create requires meldingType")
        }
        publisher.publish(delivery.reference, dittSykefravaer)
        return DeliveryOutcome.Sent
    }
}
