package no.nav.budstikka.domain.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }

class FormidlingTest :
    FunSpec({
        // B9: Personident.toString() skal aldri lekke fnr til logg
        context("Personident PII-maskering") {
            test("toString returnerer *** uavhengig av verdi") {
                val fnr = "12345678901"
                Personident(fnr).toString() shouldBe "***"
            }

            test("value-feltet er tilgjengelig direkte") {
                Personident("12345678901").value shouldBe "12345678901"
            }

            test("interpolering i logg-streng lekker ikke fnr") {
                val ident = Personident("12345678901")
                val loggLinje = "Behandler melding for $ident"
                loggLinje shouldNotContain "12345678901"
                loggLinje shouldBe "Behandler melding for ***"
            }
        }

        context("Serialisering round-trip") {
            test("BrukervarselOpprett serialiseres og deserialiseres korrekt") {
                val original =
                    Formidling(
                        eventId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        referanse = "ref-123",
                        innhold =
                            BrukervarselOpprett(
                                personident = Personident("12345678901"),
                                varseltype = Varseltype.BESKJED,
                                tekst = "Du har en ny oppgave",
                                lenke = "https://nav.no/oppgave",
                            ),
                    )
                val serialisert = json.encodeToString(original)
                val deserialisert = json.decodeFromString<Formidling>(serialisert)
                deserialisert shouldBe original
            }

            test("ArbeidsgivervarselOpprett med NarmesteLeder round-trip") {
                val original =
                    Formidling(
                        eventId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                        referanse = "ref-ag-456",
                        innhold =
                            ArbeidsgivervarselOpprett(
                                orgnummer = Orgnummer("999999999"),
                                mottaker = NarmesteLeder(sykmeldt = Personident("12345678901")),
                                merkelapp = Merkelapp.DIALOGMOETE,
                                tekst = "Møteinnkalling",
                                lenke = "https://nav.no/mote",
                                meldingstype = AgMeldingstype.OPPGAVE,
                            ),
                    )
                val serialisert = json.encodeToString(original)
                val deserialisert = json.decodeFromString<Formidling>(serialisert)
                deserialisert shouldBe original
            }

            test("BrukervarselInaktiver round-trip") {
                val original =
                    Formidling(
                        eventId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                        referanse = "ref-123",
                        innhold =
                            BrukervarselInaktiver(
                                referanse = "ref-123",
                                sykmeldt = Personident("12345678901"),
                            ),
                    )
                val serialisert = json.encodeToString(original)
                val deserialisert = json.decodeFromString<Formidling>(serialisert)
                deserialisert shouldBe original
            }

            test("type-diskriminator er med i serialisert JSON") {
                val formidling =
                    Formidling(
                        eventId = UUID.fromString("00000000-0000-0000-0000-000000000004"),
                        referanse = "ref",
                        innhold =
                            BrevOpprett(
                                personident = Personident("12345678901"),
                                journalpostId = "jp-123",
                            ),
                    )
                val serialisert = json.encodeToString(formidling)
                // distribusjonstype er ikke med fordi det er default-verdi (kotlinx.serialization utelater defaults)
                serialisert shouldBe
                    """{"eventId":"00000000-0000-0000-0000-000000000004","referanse":"ref","innhold":{"type":"BrevOpprett","personident":"12345678901","journalpostId":"jp-123"}}"""
            }

            test("partisjonsnokkel er ikke med i serialisert JSON") {
                val formidling =
                    Formidling(
                        eventId = UUID.fromString("00000000-0000-0000-0000-000000000005"),
                        referanse = "ref",
                        innhold =
                            DittSykefravaerOpprett(
                                personident = Personident("12345678901"),
                                tekst = "Hei",
                            ),
                    )
                val serialisert = json.encodeToString(formidling)
                serialisert shouldNotContain "partisjonsnokkel"
            }
        }

        context("FormidlingEnvelope envelope-only parse") {
            test("parser eventId og referanse uten å dekode innhold") {
                val payload =
                    """
                    {
                      "eventId": "aaaaaaaa-0000-0000-0000-000000000001",
                      "referanse": "ref-abc",
                      "innhold": {
                        "type": "BrukervarselOpprett",
                        "personident": "12345678901",
                        "varseltype": "BESKJED",
                        "tekst": "Hei"
                      }
                    }
                    """.trimIndent()
                val envelope = json.decodeFromString<FormidlingEnvelope>(payload)
                envelope.eventId shouldBe UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
                envelope.referanse shouldBe "ref-abc"
            }
        }
    })
