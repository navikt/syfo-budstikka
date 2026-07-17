package no.nav.budstikka.application

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.DistributionType
import no.nav.budstikka.application.port.DistributionType as PortDistributionType

class BrevChannelHandler(
    private val documentDistributor: DocumentDistributor,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val brev =
            delivery.payload as? BrevCreate
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match BREV channel: ${delivery.payload::class.simpleName}",
                )

        return when (val response = documentDistributor.distribute(delivery.toDistributionRequest(brev))) {
            is DistributionResponse.Ok -> DeliveryOutcome.Sent
            is DistributionResponse.NotOk -> DeliveryOutcome.Failed(response.reason)
        }
    }

    private fun ClaimedDelivery.toDistributionRequest(brev: BrevCreate): DistributionRequest =
        DistributionRequest(
            journalpostId = brev.journalpostId,
            distributionType = brev.distributionType.toPortDistributionType(),
            eventId = inboxEventId ?: id,
            forceCentralPrint = true,
        )

    private fun DistributionType.toPortDistributionType(): PortDistributionType =
        when (this) {
            DistributionType.IMPORTANT -> PortDistributionType.VIKTIG
            DistributionType.OTHER -> PortDistributionType.ANNET
        }
}
