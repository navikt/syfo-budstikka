package no.nav.budstikka.infrastructure.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.PdlConfig

class PdlNameTest :
    FunSpec({
        test("HTTP failures cannot become a resolved null name even if the body contains an empty name result") {
            val engine =
                MockEngine {
                    respond("""{"data":{"hentPerson":{"navn":[]}}}""", HttpStatusCode.ServiceUnavailable)
                }
            HttpClient(engine).use { http ->
                val client =
                    PdlClient(
                        http,
                        PdlConfig("https://pdl.test/graphql", "synthetic-scope", "synthetic-purpose"),
                        object : TokenProvider {
                            override suspend fun token(target: String): String = "synthetic"
                        },
                    )
                shouldThrow<IllegalStateException> {
                    client.findName(PersonIdentifier("00000000000"))
                }.message shouldBe "PDL name lookup failed with status 503"
            }
        }

        test("missing GraphQL data without an error is invalid, not a resolved null name") {
            shouldThrow<IllegalStateException> { PdlClient.parseName("{}") }.message shouldBe "PDL name response has no data"
        }

        test("the name query asks only for the name") {
            PdlClient.nameQuery("00000000000").query.apply {
                this shouldContain "navn { fornavn mellomnavn etternavn }"
                this shouldNotContain "doedsfall"
            }
        }

        test("parses the current name") {
            PdlClient.parseName(
                """{"data":{"hentPerson":{"navn":[{"fornavn":"SYNTETISK","mellomnavn":null,"etternavn":"SYKMELDT"}]}}}""",
            ) shouldBe PersonName("SYNTETISK", null, "SYKMELDT")
        }

        test("a person without a name gives no name") {
            PdlClient.parseName("""{"data":{"hentPerson":{"navn":[]}}}""") shouldBe null
            PdlClient.parseName("""{"data":{"hentPerson":null}}""") shouldBe null
        }

        test("failures carry neither the response nor its error messages") {
            shouldThrow<IllegalStateException> {
                PdlClient.parseName("""{"errors":[{"message":"Fant ikke SYNTETISK SYKMELDT"}]}""")
            }.apply {
                message shouldNotContain "SYNTETISK"
                cause shouldBe null
            }
            shouldThrow<IllegalStateException> {
                PdlClient.parseName("""{"data":{"hentPerson":{"navn":[{"fornavn":"SYNTETISK"""")
            }.apply {
                message shouldNotContain "SYNTETISK"
                cause shouldBe null
            }
            Name("SYNTETISK", null, "SYKMELDT").toString() shouldNotContain "SYNTETISK"
        }
    })
