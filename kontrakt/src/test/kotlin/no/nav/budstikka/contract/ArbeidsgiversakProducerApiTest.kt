package no.nav.budstikka.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID

private const val REFERENCE = "ref-1"
private val EVENT_ID = EventId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

/**
 * Golden tests for the producer-facing arbeidsgiversak and saksstatus API: the exact bytes a
 * Produsent puts on the topic. Split out of [BudstikkaProducerApiTest]; the same rule applies. A
 * change to any expected string here is a wire change for every consumer and producer of
 * `budstikka.v1`, and must be treated as one.
 */
class ArbeidsgiversakProducerApiTest :
    FunSpec({
        context("arbeidsgiversak") {
            test("a notification to Nærmeste leder carries the sakstype instead of a tag") {
                val encoded =
                    Budstikka.arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                        sendingWindow = SendingWindow.ONGOING,
                        saksstatus = Arbeidsgiversak.Status.FERDIG,
                    )

                encoded.key shouldBe SYNTHETIC_ORGNUMMER.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":null},"text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/ag",""" +
                    """"meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,"sendingWindow":"ONGOING",""" +
                    """"sakstype":"DIALOGMOTE","saksstatus":"FERDIG"}}"""
            }

            test("a notification to an Altinn resource carries the Sykmeldt and the producer's sak key") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.AltinnResource("nav_syfo_dialogmote"),
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakId = "dialog-1",
                        htmlEmail = Arbeidsgivervarsel.HtmlEmailNotification("Tittel", "<p>E-post</p>"),
                        smsText = "SMS",
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","smsText":"SMS",""" +
                    """"emailBodyFormat":"HTML"},"sykmeldt":"00000000000","sakId":"dialog-1"},"text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/ag","meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS","sakstype":"DIALOGMOTE"}}"""
            }

            test("an Altinn notification without HTML keeps the recipient's plain-text notification") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient =
                            Arbeidsgivervarsel.AltinnResource(
                                "nav_syfo_dialogmote",
                                Arbeidsgivervarsel.AltinnExternalNotification("Tittel", "E-post", "SMS"),
                            ),
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        sakId = "dialog-1",
                        sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldContain
                    """"mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"E-post","smsText":"SMS"},""" +
                    """"sykmeldt":"00000000000","sakId":"dialog-1"}"""
            }

            fun altinnWithSakId(sakId: String) =
                Budstikka.arbeidsgivervarselCreate(
                    eventId = EVENT_ID,
                    reference = REFERENCE,
                    orgnummer = SYNTHETIC_ORGNUMMER,
                    recipient = Arbeidsgivervarsel.AltinnResource("nav_syfo_dialogmote"),
                    sykmeldt = SYNTHETIC_SYKMELDT,
                    sakId = sakId,
                    sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                    text = SYNTHETIC_TEXT,
                    link = "https://nav.no/ag",
                )

            test("an Altinn sak key may be a number or a UUID") {
                altinnWithSakId("4711").value shouldContain """"sakId":"4711""""
                altinnWithSakId("0f8fad5b-d9cb-469f-a165-a1b2c3d4e5f6").value shouldContain "\"sakId\":\"0f8fad5b"
            }

            listOf(" ", "", "dialog:2", "dialog 1", "x".repeat(65)).forEach { sakId ->
                test("an Altinn sak key of [$sakId] is rejected without echoing it") {
                    shouldThrow<IllegalArgumentException> { altinnWithSakId(sakId) }.message shouldBe
                        "sakId must be 1-64 letters, digits, '-' or '_'"
                }
            }

            mapOf("11 digits" to "1".repeat(11), "the Sykmeldt" to "dialog-${SYNTHETIC_SYKMELDT.value}").forEach { (what, sakId) ->
                test("an Altinn sak key containing $what is rejected without echoing it") {
                    shouldThrow<IllegalArgumentException> { altinnWithSakId(sakId) }.message shouldBe
                        "sakId must not be a personident"
                }
            }

            listOf(
                Arbeidsgiversak.Type.DIALOGMOTE to "DIALOGMOTE",
                Arbeidsgiversak.Type.OPPFOLGINGSPLAN to "OPPFOLGINGSPLAN",
            ).also { pairs -> pairs.map { it.first }.toSet() shouldBe Arbeidsgiversak.Type.entries.toSet() }
                .forEach { (sakstype, wireValue) ->
                    test("sakstype $sakstype maps to $wireValue") {
                        Budstikka
                            .arbeidsgivervarselCreate(
                                eventId = EVENT_ID,
                                reference = REFERENCE,
                                orgnummer = SYNTHETIC_ORGNUMMER,
                                recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                                sakstype = sakstype,
                                text = SYNTHETIC_TEXT,
                                link = "https://nav.no/ag",
                            ).value shouldContain """"sakstype":"$wireValue""""
                    }
                }
        }

        context("saksstatus") {
            listOf(
                Arbeidsgiversak.Status.MOTTATT to "MOTTATT",
                Arbeidsgiversak.Status.UNDER_BEHANDLING to "UNDER_BEHANDLING",
                Arbeidsgiversak.Status.FERDIG to "FERDIG",
            ).also { pairs -> pairs.map { it.first }.toSet() shouldBe Arbeidsgiversak.Status.entries.toSet() }
                .forEach { (status, wireValue) ->
                    test("saksstatus $status maps to $wireValue") {
                        Budstikka
                            .arbeidsgivervarselCreate(
                                eventId = EVENT_ID,
                                reference = REFERENCE,
                                orgnummer = SYNTHETIC_ORGNUMMER,
                                recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                                sakstype = Arbeidsgiversak.Type.DIALOGMOTE,
                                text = SYNTHETIC_TEXT,
                                link = "https://nav.no/ag",
                                saksstatus = status,
                            ).value shouldContain """"saksstatus":"$wireValue""""
                    }
                }
        }
    })
