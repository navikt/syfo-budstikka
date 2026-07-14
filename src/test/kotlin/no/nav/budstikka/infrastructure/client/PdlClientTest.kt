package no.nav.budstikka.infrastructure.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.PdlConfig

private class RecordingTokenProvider(
    private val token: String,
) : TokenProvider {
    var requestedTarget: String? = null

    override suspend fun token(target: String): String {
        requestedTarget = target
        return token
    }
}

class PdlClientTest :
    FunSpec({
        val config = PdlConfig(url = "http://pdl-api.pdl/graphql", scope = "api://dev.pdl.pdl-api/.default")

        fun pdlBody(dead: Boolean): String =
            if (dead) {
                """{"data":{"hentPerson":{"doedsfall":[{"doedsdato":"2020-01-01"}]}}}"""
            } else {
                """{"data":{"hentPerson":{"doedsfall":[]}}}"""
            }

        test("doedsfall med oppføring tolkes som død") {
            PdlClient.parseIsDead(pdlBody(dead = true)) shouldBe true
        }

        test("tom doedsfall-liste tolkes som ikke død") {
            PdlClient.parseIsDead(pdlBody(dead = false)) shouldBe false
        }

        test("manglende doedsfall-felt tolkes som ikke død") {
            PdlClient.parseIsDead("""{"data":{"hentPerson":{}}}""") shouldBe false
        }

        test("GraphQL-errors kaster i stedet for å tolkes som ikke død") {
            val body = """{"errors":[{"message":"Ikke autentisert"}],"data":null}"""
            shouldThrow<IllegalStateException> { PdlClient.parseIsDead(body) }
                .message shouldContain "Ikke autentisert"
        }

        test("ukjente felter ignoreres (framoverkompatibel)") {
            val body = """{"data":{"hentPerson":{"doedsfall":[{"doedsdato":"2020-01-01","ukjent":1}]}},"extensions":{}}"""
            PdlClient.parseIsDead(body) shouldBe true
        }

        test("request bærer ident som variabel") {
            PdlClient.personQuery("11111111111").variables.ident shouldBe "11111111111"
        }

        test("isDead veksler token for PDL-scopet og sender det som Bearer mot pdl.url") {
            val tokenProvider = RecordingTokenProvider("tok-42")
            var capturedUrl: String? = null
            var capturedAuth: String? = null
            var capturedBody: String? = null
            val httpClient =
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        capturedAuth = request.headers[HttpHeaders.Authorization]
                        capturedBody = (request.body as io.ktor.http.content.TextContent).text
                        respond(
                            content = pdlBody(dead = true),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val dead = PdlClient(httpClient, config, tokenProvider).isDead(PersonIdentifier("11111111111"))

            dead shouldBe true
            tokenProvider.requestedTarget shouldBe config.scope
            capturedUrl shouldBe config.url
            capturedAuth shouldBe "Bearer tok-42"
            capturedBody!! shouldContain "11111111111"
        }

        test("isDead returnerer false når PDL ikke har doedsfall") {
            val httpClient =
                HttpClient(
                    MockEngine {
                        respond(
                            content = pdlBody(dead = false),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            PdlClient(httpClient, config, RecordingTokenProvider("tok")).isDead(PersonIdentifier("22222222222")) shouldBe false
        }
    })
