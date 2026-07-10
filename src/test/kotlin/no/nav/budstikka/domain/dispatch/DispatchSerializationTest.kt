package no.nav.budstikka.domain.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Instant

class DispatchSerializationTest :
    FunSpec({
        val fnr = "12345678901"
        val orgnr = "987654321"

        context("de/serialisering rundtur bevarer alle variants") {
            val variants: List<Pair<String, DispatchContent>> =
                listOf(
                    "BrukervarselCreate" to
                        BrukervarselCreate(
                            personIdentifier = PersonIdentifier(fnr),
                            varseltype = Varseltype.OPPGAVE,
                            text = "Du har en oppgave",
                            link = "https://nav.no/x",
                            visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                            externalVarsling = ExternalVarsling(smsText = "Sjekk Min side"),
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                            sendingWindow = SendingWindow.NKS_OPENING_HOURS,
                        ),
                    "LedervarselCreate" to
                        LedervarselCreate(
                            sykmeldt = PersonIdentifier(fnr),
                            orgnummer = Orgnummer(orgnr),
                            text = "Din ansatte",
                        ),
                    "DittSykefravaerCreate" to
                        DittSykefravaerCreate(
                            personIdentifier = PersonIdentifier(fnr),
                            text = "Nytt på Ditt sykefravær",
                        ),
                    "ArbeidsgivervarselCreate-NL" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(orgnr),
                            recipient = NarmesteLeder(sykmeldt = PersonIdentifier(fnr)),
                            tag = Tag.DIALOGMOETE,
                            text = "Dialogmøte",
                            link = "https://nav.no/ag",
                            meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                            sakstilknytning = Sakstilknytning(sakId = "sak-1"),
                        ),
                    "ArbeidsgivervarselCreate-Altinn" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(orgnr),
                            recipient = AltinnResource(resource = AltinnResourceId.DIALOGMOETE),
                            tag = Tag.OPPFOELGING,
                            text = "Oppfølging",
                            link = "https://nav.no/ag",
                        ),
                    "BrevCreate" to
                        BrevCreate(
                            personIdentifier = PersonIdentifier(fnr),
                            journalpostId = "jp-2",
                        ),
                    "MicrofrontendEnable" to
                        MicrofrontendEnable(
                            personIdentifier = PersonIdentifier(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "MikrofrontendDisable" to
                        MicrofrontendDisable(
                            personIdentifier = PersonIdentifier(fnr),
                            mikrofrontendId = "mf-1",
                        ),
                    "BrukervarselInactivate" to
                        BrukervarselInactivate(reference = "ref-123", sykmeldt = PersonIdentifier(fnr)),
                    "LedervarselInactivate" to
                        LedervarselInactivate(reference = "ref-123", sykmeldt = PersonIdentifier(fnr)),
                    "DittSykefravaerInactivate" to
                        DittSykefravaerInactivate(reference = "ref-123", sykmeldt = PersonIdentifier(fnr)),
                    "ArbeidsgivervarselInactivate" to
                        ArbeidsgivervarselInactivate(reference = "ref-123", orgnummer = Orgnummer(orgnr)),
                )

            variants.forEach { (name, content) ->
                test("rundtur bevarer $name") {
                    rundtur(content) shouldBe envelope(content)
                }
            }
        }

        test("polymorf diskriminator bruker stabilt type-name") {
            val json = dispatchJson.encodeToString(envelope(BrevCreate(PersonIdentifier(fnr), "jp-9")))
            json shouldContain "\"type\":\"BrevCreate\""
        }

        test("partitionKey serialiseres ikke (computed getter uten backing field)") {
            val json = dispatchJson.encodeToString(envelope(BrevCreate(PersonIdentifier(fnr), "jp-9")))
            json shouldNotContain "partitionKey"
        }

        context("PII-maskering i toString (B9)") {
            test("Personident maskeres") {
                PersonIdentifier(fnr).toString() shouldBe "***"
            }

            test("Orgnummer maskeres") {
                Orgnummer(orgnr).toString() shouldBe "***"
            }

            test("data class-toString lekker ikke fnr eller orgnr") {
                val content =
                    ArbeidsgivervarselCreate(
                        orgnummer = Orgnummer(orgnr),
                        recipient = NarmesteLeder(sykmeldt = PersonIdentifier(fnr)),
                        tag = Tag.DIALOGMOETE,
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
            val json = dispatchJson.encodeToString(envelope(BrevCreate(PersonIdentifier(fnr), "jp-9")))
            json shouldContain fnr
        }
    })
