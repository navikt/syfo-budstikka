package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DocumentDistributor

class FakeDocumentDistributor : DocumentDistributor {
    val requests = mutableListOf<DistributionRequest>()

    override suspend fun distribute(request: DistributionRequest): DistributionResponse {
        requests += request
        return DistributionResponse.Ok(orderId = "local-${request.eventId}")
    }
}
