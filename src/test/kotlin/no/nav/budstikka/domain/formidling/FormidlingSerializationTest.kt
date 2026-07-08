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

        context("de/serialisering rundtur bevarer alle varianter") {
            val varianter: List<Pair<String, Formidlingsinnhold>> =
                listOf(
                    "BrukervarselOpprett" to
                        BrukervarselOpprett(
                            personident = Personident(fnr),
                            varseltype = Varseltype.OPPGAVE,
                            tekst = "Du har en oppgave",
                            lenke = "https://nav.no/x",
                            synligTom = Instant.parse("2026-01-01T00:00:00Z"),
                            eksternVarsling = EksternVarsling(smsTekst = "Sjekk Min side"),
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                            sendevindu = Sendevindu.NKS_AAPNINGSTID,
                        ),
                    "LedervarselOpprett" to
                        LedervarselOpprett(
                            sykmeldt = Personident(fnr),
                            orgnummer = Orgnummer(orgnr),
                            tekst = "Din ansatte",
                        ),
                    "DittSykefravaerOpprett" to
                        DittSykefravaerOpprett(
                            personident = Personident(fnr),
                            tekst = "Nytt på Ditt sykefravær",
                        ),
                    "ArbeidsgivervarselOpprett-NL" to
                        ArbeidsgivervarselOpprett(
                            orgnummer = Orgnummer(orgnr),
                            mottaker = NarmesteLeder(sykmeldt = Personident(fnr)),
                            merkelapp = Merkelapp.DIALOGMOETE,
                            tekst = "Dialogmøte",
                            lenke = "https://nav.no/ag",
                            meldingstype = AgMeldingstype.OPPGAVE,
                            sakstilknytning = Sakstilknytning(sakId = "sak-1"),
                        ),
                    "ArbeidsgivervarselOpprett-Altinn" to
                        ArbeidsgivervarselOpprett(
                            orgnummer = Orgnummer(orgnr),
                            mottaker = AltinnRessurs(ressurs = AltinnRessursId.DIALOGMOETE),
                            merkelapp = Merkelapp.OPPFOELGING,
                            tekst = "Oppfølging",
                            lenke = "https://nav.no/ag",
                        ),
                    "BrevOpprett" to
                        BrevOpprett(
                            personident = Personident(fnr),
                            journalpostId = "jp-2",
                        ),
                    "MikrofrontendAktiver" to
                        MikrofrontendAktiver(
                            personident = Personident(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "MikrofrontendDeaktiver" to
                        MikrofrontendDeaktiver(
                            personident = Personident(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "BrukervarselInaktiver" to
                        BrukervarselInaktiver(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "LedervarselInaktiver" to
                        LedervarselInaktiver(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "DittSykefravaerInaktiver" to
                        DittSykefravaerInaktiver(referanse = "ref-123", sykmeldt = Personident(fnr)),
                    "ArbeidsgivervarselInaktiver" to
                        ArbeidsgivervarselInaktiver(referanse = "ref-123", orgnummer = Orgnummer(orgnr)),
                )

            varianter.forEach { (navn, innhold) ->
                test("rundtur bevarer $navn") {
                    rundtur(innhold) shouldBe envelope(innhold)
                }
            }
        }

        test("polymorf diskriminator bruker stabilt type-navn") {
            val json = formidlingJson.encodeToString(envelope(BrevOpprett(Personident(fnr), "jp-9")))
            json shouldContain "\"type\":\"BrevOpprett\""
        }

        test("partisjonsnokkel serialiseres ikke (computed getter uten backing field)") {
            val json = formidlingJson.encodeToString(envelope(BrevOpprett(Personident(fnr), "jp-9")))
            json shouldNotContain "partisjonsnokkel"
        }

        context("PII-maskering i toString (B9)") {
            test("Personident maskeres") {
                Personident(fnr).toString() shouldBe "***"
            }

            test("Orgnummer maskeres") {
                Orgnummer(orgnr).toString() shouldBe "***"
            }

            test("data class-toString lekker ikke fnr eller orgnr") {
                val innhold =
                    ArbeidsgivervarselOpprett(
                        orgnummer = Orgnummer(orgnr),
                        mottaker = NarmesteLeder(sykmeldt = Personident(fnr)),
                        merkelapp = Merkelapp.DIALOGMOETE,
                        tekst = "Dialogmøte",
                        lenke = "https://nav.no/ag",
                    )

                with(envelope(innhold).toString()) {
                    this shouldNotContain fnr
                    this shouldNotContain orgnr
                    this shouldContain "***"
                }
            }
        }

        test("serialisert payload bærer råverdien (partisjonering trenger ekte id)") {
            val json = formidlingJson.encodeToString(envelope(BrevOpprett(Personident(fnr), "jp-9")))
            json shouldContain fnr
        }
    })
