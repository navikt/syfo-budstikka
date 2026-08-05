// Wire-level tests: the envelope and the serialised content DTOs are the subject here.
@file:OptIn(InternalBudstikkaWire::class)

package no.nav.budstikka.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.time.Instant

class DispatchSerializationTest :
    FunSpec({
        context("serialization roundtrip preserves all variants") {
            val variants: List<Pair<String, DispatchContent>> =
                listOf(
                    "BrukervarselCreate" to
                        BrukervarselCreate(
                            personIdentifier = SYNTHETIC_SYKMELDT,
                            varseltype = Varseltype.OPPGAVE,
                            text = "Du har en oppgave",
                            link = "https://nav.no/x",
                            visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                            externalVarsling = ExternalVarsling.smsAndEmail(smsText = SYNTHETIC_SMS_TEXT),
                            brevFallback = BrevFallback(journalpostId = "jp-1"),
                            sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                        ),
                    "LedervarselCreate" to
                        LedervarselCreate(
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = "Din ansatte",
                        ),
                    "DittSykefravaerCreate" to
                        DittSykefravaerCreate(
                            personIdentifier = SYNTHETIC_SYKMELDT,
                            text = "Nytt på Ditt sykefravær",
                        ),
                    "ArbeidsgivervarselCreate-NL" to
                        ArbeidsgivervarselCreate(
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            recipient = NarmesteLeder(sykmeldt = SYNTHETIC_SYKMELDT),
                            tag = Tag.DIALOGMOETE,
                            text = "Dialogmøte",
                            link = "https://nav.no/ag",
                            meldingstype = ArbeidsgiverMeldingstype.OPPGAVE,
                            sakstilknytning = Sakstilknytning(sakId = "sak-1"),
                        ),
                    "ArbeidsgivervarselCreate-Altinn" to
                        ArbeidsgivervarselCreate(
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            recipient = AltinnResource(resource = AltinnResourceId.DIALOGMOETE),
                            tag = Tag.OPPFOELGING,
                            text = "Oppfølging",
                            link = "https://nav.no/ag",
                        ),
                    "BrevCreate" to
                        BrevCreate(
                            personIdentifier = SYNTHETIC_SYKMELDT,
                            journalpostId = "jp-2",
                        ),
                    "MicrofrontendEnable" to
                        MicrofrontendEnable(
                            personIdentifier = SYNTHETIC_SYKMELDT,
                            microfrontendId = "mf-1",
                        ),
                    "MicrofrontendDisable" to
                        MicrofrontendDisable(
                            personIdentifier = SYNTHETIC_SYKMELDT,
                            microfrontendId = "mf-1",
                        ),
                    "BrukervarselInactivate" to
                        BrukervarselInactivate(reference = "ref-123", sykmeldt = SYNTHETIC_SYKMELDT),
                    "LedervarselInactivate" to
                        LedervarselInactivate(reference = "ref-123", sykmeldt = SYNTHETIC_SYKMELDT),
                    "DittSykefravaerInactivate" to
                        DittSykefravaerInactivate(reference = "ref-123", sykmeldt = SYNTHETIC_SYKMELDT),
                    "ArbeidsgivervarselInactivate" to
                        ArbeidsgivervarselInactivate(
                            reference = "ref-123",
                            orgnummer = SYNTHETIC_ORGNUMMER,
                        ),
                )

            variants.forEach { (name, content) ->
                test("roundtrip preserves $name") {
                    roundtrip(content) shouldBe envelope(content)
                }
            }
        }

        test("polymorphic discriminator uses a stable type name") {
            dispatchJson.encodeToString(
                envelope(
                    BrevCreate(
                        SYNTHETIC_SYKMELDT,
                        "jp-9",
                    ),
                ),
            ) shouldContain "\"type\":\"BrevCreate\""
        }

        test("partitionKey is not serialized (computed getter without backing field)") {
            dispatchJson.encodeToString(envelope(BrevCreate(SYNTHETIC_SYKMELDT, "jp-9"))) shouldNotContain "partitionKey"
        }

        context("SendingWindow null-tolerance (legacy messages produced before non-null migration)") {
            test("BrukervarselCreate with sendingWindow: null maps to BUDSTIKKA_OPENING_HOURS") {
                val payload =
                    """{"reference":"ref-1","content":{"type":"BrukervarselCreate","personIdentifier":"${SYNTHETIC_SYKMELDT.value}","varseltype":"BESKJED","text":"Hei","sendingWindow":null}}"""
                val dispatch = dispatchJson.decodeFromString<Dispatch>(payload)
                (dispatch.content as BrukervarselCreate).sendingWindow shouldBe SendingWindow.BUDSTIKKA_OPENING_HOURS
            }

            test("ArbeidsgivervarselCreate with sendingWindow: null maps to BUDSTIKKA_OPENING_HOURS") {
                val payload =
                    """{"reference":"ref-2","content":{"type":"ArbeidsgivervarselCreate","orgnummer":"${SYNTHETIC_ORGNUMMER.value}","mottaker":{"type":"AltinnRessurs","resource":"DIALOGMOETE"},"tag":"DIALOGMOETE","text":"Hei","link":"https://nav.no","sendingWindow":null}}"""
                val dispatch = dispatchJson.decodeFromString<Dispatch>(payload)
                (dispatch.content as ArbeidsgivervarselCreate).sendingWindow shouldBe SendingWindow.BUDSTIKKA_OPENING_HOURS
            }

            test("LedervarselCreate with sendingWindow: null maps to ONGOING") {
                val payload =
                    """{"reference":"ref-3","content":{"type":"LedervarselCreate","sykmeldt":"${SYNTHETIC_SYKMELDT.value}","orgnummer":"${SYNTHETIC_ORGNUMMER.value}","oppgavetype":"DIALOGMOTE_INNKALLING","text":"Hei","sendingWindow":null}}"""
                val dispatch = dispatchJson.decodeFromString<Dispatch>(payload)
                (dispatch.content as LedervarselCreate).sendingWindow shouldBe SendingWindow.ONGOING
            }
        }

        test("serialized payload carries the raw value (partitioning needs a real id)") {
            dispatchJson.encodeToString(
                envelope(
                    BrevCreate(
                        SYNTHETIC_SYKMELDT,
                        "jp-9",
                    ),
                ),
            ) shouldContain SYNTHETIC_SYKMELDT.value
        }

        test("a payload encoded by the producer facade decodes back into the same content") {
            val encoded =
                Budstikka.brukervarselCreate(
                    eventId = EventId(UUID.fromString("00000000-0000-4000-8000-000000000003")),
                    reference = "ref-123",
                    sykmeldt = SYNTHETIC_SYKMELDT,
                    varseltype = Varseltype.OPPGAVE,
                    text = SYNTHETIC_TEXT,
                    externalVarsling = ExternalVarsling.smsAndEmail(smsText = SYNTHETIC_SMS_TEXT),
                )

            dispatchJson.decodeFromString<Dispatch>(encoded.value) shouldBe
                envelope(
                    BrukervarselCreate(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.OPPGAVE,
                        text = SYNTHETIC_TEXT,
                        externalVarsling = ExternalVarsling.smsAndEmail(smsText = SYNTHETIC_SMS_TEXT),
                    ),
                )
        }
    })
