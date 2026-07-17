package no.nav.budstikka.infrastructure.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import no.nav.budstikka.application.port.DistributionRequest
import no.nav.budstikka.application.port.DistributionResponse
import no.nav.budstikka.application.port.DistributionType
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.DocumentDistributorConfig
import no.nav.budstikka.infrastructure.config.PlatformConfig
import java.util.UUID

private class RecordingDocumentTokenProvider(
    private val token: String,
) : TokenProvider {
    var requestedTarget: String? = null

    override suspend fun token(target: String): String {
        requestedTarget = target
        return token
    }
}

class DocumentDistributionClientTest :
    FunSpec({
        val config =
            DocumentDistributorConfig(
                url = "https://dokdistfordeling/rest/v1/distribuerjournalpost",
                scope = "api://dev-fss.teamdokumenthandtering.dokdistfordeling/.default",
            )
        val platformConfig =
            PlatformConfig(
                clusterName = "dev-gcp",
                namespace = "team-esyfo",
                appName = "syfo-budstikka",
            )
        val eventId = UUID.fromString("01980c84-fdc0-7000-9000-000000000001")

        fun request(forceCentralPrint: Boolean = false) =
            DistributionRequest(
                journalpostId = "123456789",
                distributionType = DistributionType.VEDTAK,
                eventId = eventId,
                forceCentralPrint = forceCentralPrint,
            )

        fun responseBody(orderId: String = "3ea4d118-6012-4fd0-9095-0f9944568d03") = """{"bestillingsId":"$orderId"}"""

        test("distribute sends token, call id and maps 200 to Ok") {
            val tokenProvider = RecordingDocumentTokenProvider("tok-42")
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedCallId: String? = null
            var capturedBody: String? = null
            val httpClient =
                HttpClient(
                    MockEngine { httpRequest ->
                        capturedUrl = httpRequest.url.toString()
                        capturedAuth = httpRequest.headers[HttpHeaders.Authorization]
                        capturedCallId = httpRequest.headers["Nav-Call-Id"]
                        capturedBody = (httpRequest.body as TextContent).text
                        respond(
                            content = responseBody("order-1"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val response =
                DocumentDistributionClient(httpClient, config, platformConfig, tokenProvider)
                    .distribute(request(forceCentralPrint = true))

            response shouldBe DistributionResponse.Ok(orderId = "order-1")
            tokenProvider.requestedTarget shouldBe config.scope
            capturedUrl shouldBe config.url
            capturedAuth.isNullOrBlank() shouldBe false
            capturedCallId shouldBe eventId.toString()
            val body = capturedBody ?: error("Document distribution request body was not captured")
            body shouldContain """"journalpostId":"123456789""""
            body shouldContain """"distribusjonstype":"VEDTAK""""
            body shouldContain """"dokumentProdApp":"team-esyfo""""
            body shouldContain """"tvingKanal":"PRINT""""
        }

        test("distribute treats 409 with bestillingsId as idempotent Ok") {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = responseBody("existing-order"),
                            status = HttpStatusCode.Conflict,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val response =
                DocumentDistributionClient(httpClient, config, platformConfig, RecordingDocumentTokenProvider("tok"))
                    .distribute(request())

            response shouldBe DistributionResponse.Ok(orderId = "existing-order")
        }

        test("distribute maps documented permanent journalpost errors to NotOk") {
            val statuses =
                listOf(
                    HttpStatusCode.BadRequest,
                    HttpStatusCode.NotFound,
                    HttpStatusCode.Gone,
                )

            for (status in statuses) {
                val httpClient =
                    HttpClient(
                        MockEngine {
                            respond(
                                content = "",
                                status = status,
                            )
                        },
                    )

                val response =
                    DocumentDistributionClient(httpClient, config, platformConfig, RecordingDocumentTokenProvider("tok"))
                        .distribute(request())

                val notOk = response as DistributionResponse.NotOk
                notOk.reason shouldContain status.value.toString()
            }
        }

        test("distribute throws on token and technical downstream errors so delivery can retry") {
            val statuses =
                listOf(
                    HttpStatusCode.Unauthorized,
                    HttpStatusCode.InternalServerError,
                    HttpStatusCode.ServiceUnavailable,
                )

            for (status in statuses) {
                val httpClient =
                    HttpClient(
                        MockEngine {
                            respond(
                                content = """{"message":"could contain sensitive downstream details"}""",
                                status = status,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                    )

                val error =
                    shouldThrow<IllegalStateException> {
                        DocumentDistributionClient(httpClient, config, platformConfig, RecordingDocumentTokenProvider("tok"))
                            .distribute(request())
                    }

                error.message shouldContain status.value.toString()
                error.message shouldNotContain "sensitive downstream details"
            }
        }

        test("distribute throws sanitized error when success body cannot be decoded") {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{"bestillingsId":"mangler-slutt""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val error =
                shouldThrow<IllegalStateException> {
                    DocumentDistributionClient(httpClient, config, platformConfig, RecordingDocumentTokenProvider("tok"))
                        .distribute(request())
                }

            error.message shouldContain "invalid success response"
            error.message shouldNotContain "mangler-slutt"
        }
    })
