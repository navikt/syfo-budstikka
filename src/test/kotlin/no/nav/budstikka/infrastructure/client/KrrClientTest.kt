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
import io.ktor.http.headersOf
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.KrrConfig

private class RecordingKrrTokenProvider(
    private val token: String,
) : TokenProvider {
    var requestedTarget: String? = null

    override suspend fun token(target: String): String {
        requestedTarget = target
        return token
    }
}

class KrrClientTest :
    FunSpec({
        val config =
            KrrConfig(
                url = "http://digdir-krr-proxy/rest/v1/person",
                scope = "api://dev-gcp.team-rocket.digdir-krr-proxy/.default",
            )

        fun krrBody(kanVarsles: Boolean): String = """{"personident":"11111111111","kanVarsles":$kanVarsles,"reservert":${!kanVarsles}}"""

        test("kanVarsles=false tolkes som reservert") {
            KrrClient.parseIsReserved(HttpStatusCode.OK, krrBody(kanVarsles = false)) shouldBe true
        }

        test("kanVarsles=true tolkes som ikke reservert") {
            KrrClient.parseIsReserved(HttpStatusCode.OK, krrBody(kanVarsles = true)) shouldBe false
        }

        test("ukjente felter ignoreres (framoverkompatibel)") {
            KrrClient.parseIsReserved(
                HttpStatusCode.OK,
                """{"kanVarsles":false,"spraak":"nb","noeNytt":1}""",
            ) shouldBe true
        }

        test("ikke-2xx kaster med kun statuskode (aldri body med fnr)") {
            val exception =
                shouldThrow<IllegalStateException> {
                    KrrClient.parseIsReserved(HttpStatusCode.InternalServerError, """{"fnr":"11111111111"}""")
                }
            exception.message shouldContain "500"
            exception.message shouldNotContain "11111111111"
        }

        test("manglende kanVarsles-felt kaster sanitert (aldri body med fnr)") {
            val exception =
                shouldThrow<IllegalStateException> {
                    KrrClient.parseIsReserved(HttpStatusCode.OK, """{"personident":"11111111111"}""")
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain "11111111111"
        }

        test("helt korrupt (ikke-JSON) body kaster sanitert uten cause som kan bære fnr") {
            val exception =
                shouldThrow<IllegalStateException> {
                    KrrClient.parseIsReserved(HttpStatusCode.OK, "ikke-json-med-fnr-11111111111")
                }
            exception.message shouldContain "200"
            exception.message shouldNotContain "11111111111"
            // Ingen cause: kotlinx-exceptionen (som kan gjengi body m/fnr) skal ikke kjedes videre.
            exception.cause shouldBe null
        }

        test("isReserved veksler token for KRR-scopet og sender fnr i Nav-Personident mot krr.url") {
            val tokenProvider = RecordingKrrTokenProvider("tok-42")
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedPersonident: String? = null
            val httpClient =
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        capturedAuth = request.headers[HttpHeaders.Authorization]
                        capturedPersonident = request.headers["Nav-Personident"]
                        respond(
                            content = krrBody(kanVarsles = false),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val reserved = KrrClient(httpClient, config, tokenProvider).isReserved(PersonIdentifier("11111111111"))

            reserved shouldBe true
            tokenProvider.requestedTarget shouldBe config.scope
            capturedUrl shouldBe config.url
            capturedAuth shouldBe "Bearer tok-42"
            capturedPersonident shouldBe "11111111111"
        }
    })
