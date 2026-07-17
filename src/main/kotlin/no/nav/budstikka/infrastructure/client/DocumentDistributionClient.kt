package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.DocumentDistributorConfig
import no.nav.budstikka.infrastructure.config.PlatformConfig

@Serializable
internal enum class ForcedChannel {
    PRINT,
}

@Serializable
internal data class JournalpostdistribusjonRequest(
    val journalpostId: String,
    val bestillendeFagsystem: String = "UKJENT",
    val dokumentProdApp: String,
    val distribusjonstype: String,
    val distribusjonstidspunkt: String = "UMIDDELBART",
    val tvingKanal: ForcedChannel? = null,
)

@Serializable
internal data class JournalpostdistribusjonResponse(
    val bestillingsId: String,
)

/**
 * Documentation: https://dokdistfordeling-q1.intern.dev.nav.no/swagger-ui/index.html#/distribuerJournalpost%20API/distribuerJournalpost
 */
class DocumentDistributionClient(
    private val httpClient: HttpClient,
    private val config: DocumentDistributorConfig,
    private val platformConfig: PlatformConfig,
    private val tokenProvider: TokenProvider,
) : DocumentDistributor {
    override suspend fun distribute(request: DistributionRequest): DistributionResponse {
        val token = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                header(NAV_CALL_ID_HEADER, request.eventId.toString())
                setBody(json.encodeToString(request.toJournalRequest()))
            }
        return response.toDistributionResponse()
    }

    private suspend fun HttpResponse.toDistributionResponse(): DistributionResponse =
        when (status) {
            HttpStatusCode.OK,
            HttpStatusCode.Conflict,
            -> DistributionResponse.Ok(orderId = parseOrderId())

            HttpStatusCode.BadRequest ->
                DistributionResponse.NotOk("Dokdistfordeling rejected the distribution request with status 400")

            HttpStatusCode.NotFound ->
                DistributionResponse.NotOk("Dokdistfordeling could not find the journalpost with status 404")

            HttpStatusCode.Gone ->
                DistributionResponse.NotOk(
                    "Dokdistfordeling cannot distribute the journalpost because the recipient is dead and address is unknown with status 410",
                )

            HttpStatusCode.Unauthorized ->
                error("Dokdistfordeling rejected the token with status ${status.value}")

            HttpStatusCode.InternalServerError,
            HttpStatusCode.ServiceUnavailable,
            -> error("Dokdistfordeling failed with status ${status.value}")

            else -> {
                if (status.value in 500..599) {
                    error("Dokdistfordeling failed with status ${status.value}")
                }
                error("Dokdistfordeling returned unexpected status ${status.value}")
            }
        }

    private suspend fun HttpResponse.parseOrderId(): String =
        try {
            json.decodeFromString<JournalpostdistribusjonResponse>(bodyAsText()).bestillingsId
        } catch (_: SerializationException) {
            error("Dokdistfordeling returned an invalid success response with status ${status.value}")
        } catch (_: IllegalArgumentException) {
            error("Dokdistfordeling returned an invalid success response with status ${status.value}")
        }

    private companion object {
        private const val NAV_CALL_ID_HEADER = "Nav-Call-Id"
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = false
            }
    }

    private fun DistributionRequest.toJournalRequest() =
        JournalpostdistribusjonRequest(
            journalpostId = this.journalpostId,
            dokumentProdApp = platformConfig.namespace,
            distribusjonstype = this.distributionType.name,
            tvingKanal = if (this.forceCentralPrint) ForcedChannel.PRINT else null,
        )
}
