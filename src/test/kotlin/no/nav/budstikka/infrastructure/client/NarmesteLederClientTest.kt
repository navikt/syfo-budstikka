package no.nav.budstikka.infrastructure.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
                """{"narmesteLeder":{"fnr":"22222222222","epostadresser":["first@example.test","second@example.test"]}}""",
            ) shouldBe
                NarmesteLederRelasjon(
                    PersonIdentifier("22222222222"),
                    listOf("first@example.test", "second@example.test"),
                )
        }

        test("returns null when no active relation exists") {
            NarmesteLederClient
                .parseActive(HttpStatusCode.OK, """{"narmesteLeder":null}""") shouldBe null
        }

        test("maps an empty email list") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"narmesteLeder":{"fnr":"22222222222","epostadresser":[]}}""",
                    ),
                )
            relation.epostadresser shouldBe emptyList()
        }

        test("ignores unknown relation fields") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"narmesteLeder":{"fnr":"22222222222","epostadresser":[],"ukjent":"verdi"}}""",
                    ),
                )
            relation.narmesteLederFnr shouldBe PersonIdentifier("22222222222")
        }

        test("findActive calls the internal lookup endpoint with required query, header, and token") {
            val tokenProvider = RecordingNarmesteLederTokenProvider("tok-42")
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedSykmeldtFnr: String? = null
            val httpClient =
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        capturedAuth = request.headers[HttpHeaders.Authorization]
                        capturedSykmeldtFnr = request.headers["Sykmeldt-Fnr"]
                        respond(
                            content = """{"narmesteLeder":null}""",
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
            capturedUrl shouldBe "${config.url}/internal/narmesteleder?orgnummer=123456789"
            capturedAuth shouldBe "Bearer tok-42"
            capturedSykmeldtFnr shouldBe "11111111111"
        }
    })
