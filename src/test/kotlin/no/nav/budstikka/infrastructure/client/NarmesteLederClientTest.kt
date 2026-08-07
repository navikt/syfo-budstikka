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
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.NarmesteLederConfig

private class RecordingNarmesteLederTokenProvider(
    private val token: String,
) : TokenProvider {
    var requestedTarget: String? = null

    override suspend fun token(target: String): String {
        requestedTarget = target
        return token
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
                """{"lineManager":{"nationalIdentificationNumber":"22222222222","emailAddresses":["first@example.test","second@example.test"]}}""",
            ) shouldBe
                NarmesteLederRelasjon(
                    PersonIdentifier("22222222222"),
                    listOf("first@example.test", "second@example.test"),
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
                        """{"lineManager":{"nationalIdentificationNumber":"22222222222","emailAddresses":[]}}""",
                    ),
                )
            relation.epostadresser shouldBe emptyList()
        }

        test("ignores unknown relation fields") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"nationalIdentificationNumber":"22222222222","emailAddresses":[],"ukjent":"verdi"}}""",
                    ),
                )
            relation.narmesteLederFnr shouldBe PersonIdentifier("22222222222")
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
                        """{"employeeNationalIdentificationNumber":"11111111111"}""",
                    )
                }
            exception.message shouldContain "500"
            exception.message shouldNotContain "11111111111"
        }

        test("a relation without nationalIdentificationNumber throws a sanitized exception") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"lineManager":{"emailAddresses":["leader-11111111111@example.test"]}}""",
                    )
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain "11111111111"
        }

        test("a completely corrupt (non-JSON) body throws sanitized without a cause that may carry fnr") {
            val exception =
                shouldThrow<IllegalStateException> {
                    NarmesteLederClient.parseActive(HttpStatusCode.OK, "ikke-json-med-fnr-11111111111")
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain "11111111111"
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
                PersonIdentifier("11111111111"),
                Orgnummer("123456789"),
            ) shouldBe null

            tokenProvider.requestedTarget shouldBe config.scope
            capturedMethod shouldBe "POST"
            capturedUrl shouldBe "${config.url}/internal/api/v1/lookup"
            capturedAuth shouldBe "Bearer tok-42"
            capturedBody shouldBe
                """{"employeeNationalIdentificationNumber":"11111111111","organizationNumber":"123456789"}"""
        }
    })
