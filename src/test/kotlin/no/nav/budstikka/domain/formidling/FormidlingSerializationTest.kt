package no.nav.budstikka.domain.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlin.time.Instant

class FormidlingSerializationTest :
    FunSpec({
        val fnr = "12345678901"
        val orgnr = "987654321"

        context("de/serialisering rundtur bevarer alle variants") {
            val variants: List<Pair<String, Formidlingsinnhold>> =
                listOf(
                    "BrukervarselCreate" to
                        BrukervarselCreate(
                            personident = Personident(fnr),
                            varseltype = Varseltype.OPPGAVE,
                            text = "Du har en oppgave",
                            link = "https://nav.no/x",
                            visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                            eksternVarsling = EksternVarsling(smsText = "Sjekk Min side"),
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                            sendevindu = Sendevindu.NKS_AAPNINGSTID,
                        ),
                    "LedervarselCreate" to
                        LedervarselCreate(
                            sykmeldt = Personident(fnr),
                            orgnummer = Orgnummer(orgnr),
                            text = "Din ansatte",
                        ),
                    "DittSykefravaerCreate" to
                        DittSykefravaerCreate(
                            personident = Personident(fnr),
                            text = "Nytt på Ditt sykefravær",
                        ),
                    "ArbeidsgivervarselCreate-NL" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(orgnr),
                            mottaker = NarmesteLeder(sykmeldt = Personident(fnr)),
                            merkelapp = Merkelapp.DIALOGMOETE,
                            text = "Dialogmøte",
                            link = "https://nav.no/ag",
                            meldingstype = AgMeldingstype.OPPGAVE,
                            sakstilknytning = Sakstilknytning(sakId = "sak-1"),
                        ),
                    "ArbeidsgivervarselCreate-Altinn" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(orgnr),
                            mottaker = AltinnRessurs(ressurs = AltinnRessursId.DIALOGMOETE),
                            merkelapp = Merkelapp.OPPFOELGING,
                            text = "Oppfølging",
                            link = "https://nav.no/ag",
                        ),
                    "BrevCreate" to
                        BrevCreate(
                            personident = Personident(fnr),
                            journalpostId = "jp-2",
                        ),
                    "MikrofrontendEnable" to
                        MikrofrontendEnable(
                            personident = Personident(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "MikrofrontendDisable" to
                        MikrofrontendDisable(
                            personident = Personident(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "BrukervarselInactivate" to
                        BrukervarselInactivate(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "LedervarselInactivate" to
                        LedervarselInactivate(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "DittSykefravaerInactivate" to
                        DittSykefravaerInactivate(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "ArbeidsgivervarselInactivate" to
                        ArbeidsgivervarselInactivate(referanse = "ref-123", orgnummer = Orgnummer(orgnr)),
                )

            variants.forEach { (name, content) ->
                test("rundtur bevarer $name") {
                    rundtur(content) shouldBe envelope(content)
                }
            }
        }

        test("polymorf diskriminator bruker stabilt type-name") {
            val json = formidlingJson.encodeToString(envelope(BrevCreate(Personident(fnr), "jp-9")))
            json shouldContain "\"type\":\"BrevCreate\""
        }

        test("partitionKey serialiseres ikke (computed getter uten backing field)") {
            val json = formidlingJson.encodeToString(envelope(BrevCreate(Personident(fnr), "jp-9")))
            json shouldNotContain "partitionKey"
        }

        context("PII-maskering i toString (B9)") {
            test("Personident maskeres") {
                Personident(fnr).toString() shouldBe "***"
            }

            test("Orgnummer maskeres") {
                Orgnummer(orgnr).toString() shouldBe "***"
            }

            test("data class-toString lekker ikke fnr eller orgnr") {
                val content =
                    ArbeidsgivervarselCreate(
                        orgnummer = Orgnummer(orgnr),
                        mottaker = NarmesteLeder(sykmeldt = Personident(fnr)),
                        merkelapp = Merkelapp.DIALOGMOETE,
                        text = "Dialogmøte",
                        link = "https://nav.no/ag",
                    )

                with(envelope(content).toString()) {
                    this shouldNotContain fnr
                    this shouldNotContain orgnr
                    this shouldContain "***"
                }
            }
        }

        test("serialisert payload bærer råverdien (partisjonering trenger ekte id)") {
            val json = formidlingJson.encodeToString(envelope(BrevCreate(Personident(fnr), "jp-9")))
            json shouldContain fnr
        }
    })
