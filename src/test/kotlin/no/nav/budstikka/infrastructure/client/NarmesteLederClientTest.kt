package no.nav.budstikka.infrastructure.client

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpStatusCode
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.domain.dispatch.PersonIdentifier

class NarmesteLederClientTest :
    FunSpec({
        test("parses active relation and splits email addresses") {
            NarmesteLederClient.parseActive(
                HttpStatusCode.OK,
                """{"narmesteLederRelasjon":{"narmesteLederFnr":"22222222222","narmesteLederEpost":" first@example.test, second@example.test "}}""",
            ) shouldBe
                NarmesteLederRelasjon(
                    PersonIdentifier("22222222222"),
                    listOf("first@example.test", "second@example.test"),
                )
        }

        test("returns null when no active relation exists") {
            NarmesteLederClient
                .parseActive(HttpStatusCode.OK, """{"narmesteLederRelasjon":null}""") shouldBe null
        }

        test("maps null or blank email to an empty list") {
            listOf("null", "\"   \"").forEach { email ->
                val relation =
                    requireNotNull(
                        NarmesteLederClient.parseActive(
                            HttpStatusCode.OK,
                            """{"narmesteLederRelasjon":{"narmesteLederFnr":"22222222222","narmesteLederEpost":$email}}""",
                        ),
                    )
                relation.epostadresser shouldBe emptyList()
            }
        }

        test("ignores unknown relation fields") {
            val relation =
                requireNotNull(
                    NarmesteLederClient.parseActive(
                        HttpStatusCode.OK,
                        """{"narmesteLederRelasjon":{"narmesteLederFnr":"22222222222","ukjent":"verdi"}}""",
                    ),
                )
            relation.narmesteLederFnr shouldBe PersonIdentifier("22222222222")
        }
    })
