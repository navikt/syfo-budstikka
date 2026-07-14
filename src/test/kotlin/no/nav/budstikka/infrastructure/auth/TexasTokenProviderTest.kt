package no.nav.budstikka.infrastructure.auth

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.auth.config.TexasConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TexasTokenProviderTest :
    FunSpec({
        val start = Instant.parse("2026-07-14T08:00:00Z")
        val config = TexasConfig(tokenEndpoint = "http://texas.local/api/v1/token", identityProvider = "entra_id")
        val target = "api://dev-gcp.pdl.pdl-api/.default"

        fun tokenResponse(
            accessToken: String,
            expiresIn: Long = 3600,
        ) = """{"access_token":"$accessToken","expires_in":$expiresIn,"token_type":"Bearer"}"""

        test("veksler M2M-token: sender identity_provider + target som form-parametre og returnerer access_token") {
            var capturedForm: Parameters? = null
            var capturedUrl: String? = null
            val client =
                HttpClient(
                    MockEngine { request ->
                        capturedUrl = request.url.toString()
                        capturedForm = (request.body as FormDataContent).formData
                        respond(
                            content = tokenResponse("tok-1"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )

            val token = TexasTokenProvider(client, config, MutableClock(start)).token(target)

            token shouldBe "tok-1"
            capturedUrl shouldBe config.tokenEndpoint
            capturedForm?.get("identity_provider") shouldBe "entra_id"
            capturedForm?.get("target") shouldBe target
        }

        test("cacher token og gjør kun ett Texas-kall for gjentatte token()-kall før utløp") {
            val calls = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        calls.incrementAndGet()
                        respond(
                            content = tokenResponse("tok-1"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val provider = TexasTokenProvider(client, config, MutableClock(start))

            provider.token(target) shouldBe "tok-1"
            provider.token(target) shouldBe "tok-1"

            calls.get() shouldBe 1
        }

        test("henter nytt token når det cachede er utløpt") {
            val issued = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = tokenResponse("tok-${issued.incrementAndGet()}", expiresIn = 3600),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val clock = MutableClock(start)
            val provider = TexasTokenProvider(client, config, clock)

            provider.token(target) shouldBe "tok-1"
            clock.current = start.plus(2.hours)
            provider.token(target) shouldBe "tok-2"

            issued.get() shouldBe 2
        }

        test("kaster uten å avsløre token når Texas svarer med feilstatus") {
            val client =
                HttpClient(
                    MockEngine {
                        respondError(
                            status = HttpStatusCode.InternalServerError,
                            content = """{"access_token":"skulle-aldri-lekke"}""",
                        )
                    },
                )
            val provider = TexasTokenProvider(client, config, MutableClock(start))

            val ex = shouldThrow<IllegalStateException> { provider.token(target) }
            ex.message shouldContain "500"
            ex.message shouldNotContain "skulle-aldri-lekke"
        }

        test("kaster sanitert uten token når Texas svarer med ugyldig JSON-body") {
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            content = """{ ikke gyldig json "access_token":"skulle-aldri-lekke" """,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val provider = TexasTokenProvider(client, config, MutableClock(start))

            val ex = shouldThrow<IllegalStateException> { provider.token(target) }
            ex.message shouldNotContain "skulle-aldri-lekke"
            generateSequence<Throwable>(ex) { it.cause }.forEach {
                it.message.orEmpty() shouldNotContain "skulle-aldri-lekke"
            }
        }

        test("samtidige kall mot samme target gjør kun ett Texas-kall (per-target lås)") {
            val calls = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine {
                        calls.incrementAndGet()
                        respond(
                            content = tokenResponse("tok-1"),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    },
                )
            val provider = TexasTokenProvider(client, config, MutableClock(start))

            coroutineScope {
                val tokens = (1..50).map { async { provider.token(target) } }.awaitAll()
                tokens.toSet() shouldBe setOf("tok-1")
            }

            calls.get() shouldBe 1
        }
    })
