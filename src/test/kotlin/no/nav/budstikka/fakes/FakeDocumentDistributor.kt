package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DocumentDistributor

/**
 * Local/test fake for document distribution. It never calls dokdist/Texas and always accepts the
 * request as distributed, so local BREV flows can be tested end-to-end without external services.
 */
class FakeDocumentDistributor : DocumentDistributor {
    val requests = mutableListOf<DistributionRequest>()

    override suspend fun distribute(request: DistributionRequest): DistributionResponse {
        requests += request
        return DistributionResponse.Ok(orderId = "local-${request.eventId}")
    }
}
