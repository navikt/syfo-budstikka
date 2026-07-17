package no.nav.budstikka.application.port

import java.util.UUID

@Suppress("unused")
enum class DistributionType {
    VEDTAK,
    VIKTIG,
    ANNET,
}

data class DistributionRequest(
    val journalpostId: String,
    val distributionType: DistributionType,
    val eventId: UUID,
    val forceCentralPrint: Boolean = false,
)

sealed interface DistributionResponse {
    data class Ok(
        val orderId: String,
    ) : DistributionResponse

    data class NotOk(
        val reason: String,
    ) : DistributionResponse
}

interface DocumentDistributor {
    suspend fun distribute(request: DistributionRequest): DistributionResponse
}
