package no.nav.budstikka.domain.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import kotlin.time.Instant

class DispatchSerializationTest :
    FunSpec({
        context("de/serialisering rundtur bevarer alle variants") {
            val variants: List<Pair<String, DispatchContent>> =
                listOf(
                    "BrukervarselCreate" to
                        BrukervarselCreate(
                            personIdentifier = TEST_SYKMELDT_2,
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
                            sykmeldt = TEST_SYKMELDT_2,
                            orgnummer = Orgnummer(TEST_ORGNUMMER.value),
                            text = "Din ansatte",
                        ),
                    "DittSykefravaerCreate" to
                        DittSykefravaerCreate(
                            personIdentifier = TEST_SYKMELDT_2,
                            text = "Nytt på Ditt sykefravær",
                        ),
                    "ArbeidsgivervarselCreate-NL" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(TEST_ORGNUMMER.value),
                            recipient = NarmesteLeder(sykmeldt = TEST_SYKMELDT_2),
                            tag = Tag.DIALOGMOETE,
                            text = "Dialogmøte",
                            link = "https://nav.no/ag",
                            meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                            sakstilknytning = Sakstilknytning(sakId = "sak-1"),
                        ),
                    "ArbeidsgivervarselCreate-Altinn" to
                        ArbeidsgivervarselCreate(
                            orgnummer = Orgnummer(TEST_ORGNUMMER.value),
                            recipient = AltinnResource(resource = AltinnResourceId.DIALOGMOETE),
                            tag = Tag.OPPFOELGING,
                            text = "Oppfølging",
                            link = "https://nav.no/ag",
                        ),
                    "BrevCreate" to
                        BrevCreate(
                            personIdentifier = TEST_SYKMELDT_2,
                            journalpostId = "jp-2",
                        ),
                    "MicrofrontendEnable" to
                        MicrofrontendEnable(
                            personIdentifier = TEST_SYKMELDT_2,
                            mikrofrontendId = "mf-1",
                        ),
                    "MikrofrontendDisable" to
                        MicrofrontendDisable(
                            personIdentifier = TEST_SYKMELDT_2,
                            mikrofrontendId = "mf-1",
                        ),
                    "BrukervarselInactivate" to
                        BrukervarselInactivate(reference = "ref-123", sykmeldt = TEST_SYKMELDT_2),
                    "LedervarselInactivate" to
                        LedervarselInactivate(reference = "ref-123", sykmeldt = TEST_SYKMELDT_2),
                    "DittSykefravaerInactivate" to
                        DittSykefravaerInactivate(reference = "ref-123", sykmeldt = TEST_SYKMELDT_2),
                    "ArbeidsgivervarselInactivate" to
                        ArbeidsgivervarselInactivate(reference = "ref-123", orgnummer = Orgnummer(TEST_ORGNUMMER.value)),
                )

            variants.forEach { (name, content) ->
                test("roundtrip preserves $name") {
                    rundtur(content) shouldBe envelope(content)
                }
            }
        }

        test("polymorphic discriminator uses a stable type name") {
            dispatchJson.encodeToString(envelope(BrevCreate(TEST_SYKMELDT_2, "jp-9"))) shouldContain "\"type\":\"BrevCreate\""
        }

        test("partitionKey is not serialized (computed getter without backing field)") {
            dispatchJson.encodeToString(envelope(BrevCreate(TEST_SYKMELDT_2, "jp-9"))) shouldNotContain "partitionKey"
        }

        context("PII-maskering i toString (B9)") {
            test("PersonIdentifier is masked") {
                TEST_SYKMELDT_2.toString() shouldBe "***"
            }

            test("Orgnummer is masked") {
                Orgnummer(TEST_ORGNUMMER.value).toString() shouldBe "***"
            }

            test("data class toString does not leak fnr or orgnr") {
                val content =
                    ArbeidsgivervarselCreate(
                        orgnummer = Orgnummer(TEST_ORGNUMMER.value),
                        recipient = NarmesteLeder(sykmeldt = TEST_SYKMELDT_2),
                        tag = Tag.DIALOGMOETE,
                        text = "Dialogmøte",
                        link = "https://nav.no/ag",
                    )

                with(envelope(content).toString()) {
                    this shouldNotContain TEST_SYKMELDT_2.value
                    this shouldNotContain TEST_ORGNUMMER.value
                    this shouldContain "***"
                }
            }
        }

        test("serialized payload carries the raw value (partitioning needs a real id)") {
            dispatchJson.encodeToString(envelope(BrevCreate(TEST_SYKMELDT_2, "jp-9"))) shouldContain TEST_SYKMELDT_2.value
        }
    })
