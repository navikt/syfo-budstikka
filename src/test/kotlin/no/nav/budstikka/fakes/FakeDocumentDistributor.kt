package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.DistributionRequest
import no.nav.budstikka.application.delivery.DistributionResponse
import no.nav.budstikka.application.delivery.DocumentDistributor

class FakeDocumentDistributor : DocumentDistributor {
    val requests = mutableListOf<DistributionRequest>()

    override suspend fun distribute(request: DistributionRequest): DistributionResponse {
        requests += request
        return DistributionResponse.Ok(orderId = "local-${request.eventId}")
    }
}
