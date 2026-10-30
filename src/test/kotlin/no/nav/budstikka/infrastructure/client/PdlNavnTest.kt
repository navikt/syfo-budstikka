package no.nav.budstikka.infrastructure.client

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.application.delivery.Personnavn

class PdlNavnTest :
    FunSpec({
        test("the name query asks only for the name") {
            PdlClient.nameQuery("00000000000").query.apply {
                this shouldContain "navn { fornavn mellomnavn etternavn }"
                this shouldNotContain "doedsfall"
            }
        }

        test("parses the current name") {
            PdlClient.parseName(
                """{"data":{"hentPerson":{"navn":[{"fornavn":"SYNTETISK","mellomnavn":null,"etternavn":"SYKMELDT"}]}}}""",
            ) shouldBe Personnavn("SYNTETISK", null, "SYKMELDT")
        }

        test("a person without a name gives no name") {
            PdlClient.parseName("""{"data":{"hentPerson":{"navn":[]}}}""") shouldBe null
            PdlClient.parseName("""{"data":{"hentPerson":null}}""") shouldBe null
        }

        test("GraphQL errors fail the lookup instead of giving no name") {
            shouldThrow<IllegalStateException> {
                PdlClient.parseName("""{"errors":[{"message":"Ikke tilgang"}]}""")
            }
        }
    })
