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
import no.nav.budstikka.application.delivery.NarmesteLederRelasjon
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.NarmesteLederConfig
import java.util.UUID

// Computed so no literal personident-shaped value sits in the source.
private val LEDER_FNR = "2".repeat(11)
private val ANSATT_FNR = "1".repeat(11)
private const val RELASJON_ID = "00000000-0000-4000-8000-00000000aa02"

private class RecordingNarmesteLederTokenProvider(
    private val issued: String,
) : TokenProvider {
    var requestedTarget: String? = null

    override suspend fun token(target: String): String {
        requestedTarget = target
        return issued
    }
}

class NarmesteLederClientTest :
    FunSpec({
        val config =
            NarmesteLederConfig(
                url = "http://esyfo-narmesteleder.team-esyfo",
                scope = "api://dev-gcp.team-esyfo.esyfo-narmesteleder/.default",
            )

        test("parses active relation") {
            NarmesteLederClient.parseActive(
                HttpStatusCode.OK,
                """{"lineManager":{"id":"$RELASJON_ID","nationalIdentificationNumber":"$LEDER_FNR","emailAddresses":["first@example.test","second@example.test"]}}""",
            ) shouldBe
                NarmesteLederRelasjon(
                    PersonIdentifier(LEDER_FNR),
                    listOf("first@example.test", "second@example.test"),
                    UUID.fromString(RELASJON_ID),
                )
        }

        test("returns null when no active relation exists") {
            NarmesteLederClient
                .parseActive(HttpStatusCode.OK, """{"lineManager":null}""") shouldBe null
        }

        test("maps an empty email list") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"id":"$RELASJON_ID","nationalIdentificationNumber":"$LEDER_FNR","emailAddresses":[]}}""",
                    ),
                )
            relation.epostadresser shouldBe emptyList()
        }

        test("ignores unknown relation fields") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"id":"$RELASJON_ID","nationalIdentificationNumber":"$LEDER_FNR","emailAddresses":[],"ukjent":"verdi"}}""",
                    ),
                )
            relation.narmesteLederFnr shouldBe PersonIdentifier(LEDER_FNR)
        }

        test("an invalid relation id throws without echoing the response") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"id":"ikke-uuid","nationalIdentificationNumber":"$LEDER_FNR","emailAddresses":[]}}""",
                    )
                }
            exception.message shouldBe "Narmeste leder register returned an invalid relation id"
            exception.message shouldNotContain LEDER_FNR
        }

        test("a missing lineManager field throws instead of being read as no active leader") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(HttpStatusCode.OK, """{"naermesteLeder":null}""")
                }
            exception.message shouldContain "200"
        }

        test("non-2xx throws with the status code only (never a body containing fnr)") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.InternalServerError,
                        """{"employeeNationalIdentificationNumber":"$ANSATT_FNR"}""",
                    )
                }
            exception.message shouldContain "500"
            exception.message shouldNotContain ANSATT_FNR
        }

        test("a relation without nationalIdentificationNumber throws a sanitized exception") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"emailAddresses":["leader-$ANSATT_FNR@example.test"]}}""",
                    )
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain ANSATT_FNR
        }

        test("a completely corrupt (non-JSON) body throws sanitized without a cause that may carry fnr") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(HttpStatusCode.OK, "ikke-json-med-fnr-$ANSATT_FNR")
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain ANSATT_FNR
            // No cause: the kotlinx exception (which may render the body with fnr) must not be chained.
            exception.cause shouldBe null
        }

        test("findActive posts the required body and token to the internal lookup endpoint") {
            val tokenProvider = RecordingNarmesteLederTokenProvider("tok-42")
            var capturedMethod: String? = null
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedBody: String? = null
            val httpClient =
                HttpClient(
                    MockEngine { request ->
                        capturedMethod = request.method.value
                        capturedUrl = request.url.toString()
                        capturedAuth = request.headers[HttpHeaders.Authorization]
                        capturedBody = (request.body as TextContent).text
                        respond(
                            content = """{"lineManager":null}""",
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            NarmesteLederClient(httpClient, config, tokenProvider).findActive(
                PersonIdentifier("$ANSATT_FNR"),
                Orgnummer("123456789"),
            ) shouldBe null

            tokenProvider.requestedTarget shouldBe config.scope
            capturedMethod shouldBe "POST"
            capturedUrl shouldBe "${config.url}/internal/api/v1/lookup"
            capturedAuth shouldBe "Bearer tok-42"
            capturedBody shouldBe
                """{"employeeNationalIdentificationNumber":"$ANSATT_FNR","organizationNumber":"123456789"}"""
        }
    })
