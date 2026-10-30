package no.nav.budstikka.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

private const val REFERENCE = "ref-1"
private val EVENT_ID = EventId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
private val VISIBLE_UNTIL = Instant.parse("2026-01-01T00:00:00Z")

/**
 * Golden tests for the producer-facing API: the exact bytes a Produsent puts on the topic. A change
 * to any expected string here is a wire change for every consumer and producer of `budstikka.v1`,
 * and must be treated as one — not as a test that needs updating.
 */
class BudstikkaProducerApiTest :
    FunSpec({
        context("brukervarselCreate") {
            test("minimal call encodes the full record, with contract defaults applied") {
                val encoded =
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.BESKJED,
                        text = SYNTHETIC_TEXT,
                    )

                encoded.topic shouldBe "team-esyfo.budstikka.v1"
                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.eventId shouldBe EVENT_ID
                encoded.headers shouldBe mapOf("eventId" to "00000000-0000-4000-8000-000000000001")
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrukervarselCreate",""" +
                    """"personIdentifier":"00000000000","varseltype":"BESKJED","text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":null,"visibleUntil":null,"externalVarsling":null,"brevFallback":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }

            test("every optional field is carried through unchanged") {
                val encoded =
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.OPPGAVE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/syk",
                        visibleUntil = VISIBLE_UNTIL,
                        externalVarsling = ExternalNotification.smsOnly(smsText = SYNTHETIC_SMS_TEXT),
                        brevFallback = BrevFallback(journalpostId = "jp-1"),
                        sendingWindow = SendingWindow.ONGOING,
                    )

                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrukervarselCreate",""" +
                    """"personIdentifier":"00000000000","varseltype":"OPPGAVE","text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/syk","visibleUntil":"2026-01-01T00:00:00Z",""" +
                    """"externalVarsling":{"channels":["SMS"],"smsText":"SYNTETISK-SMSTEKST",""" +
                    """"emailTitle":null,"emailText":null},""" +
                    """"brevFallback":{"journalpostId":"jp-1","distributionType":"IMPORTANT"},""" +
                    """"sendingWindow":"ONGOING"}}"""
            }

            test("external varsling factories set exactly the channels they name") {
                ExternalNotification.smsAndEmail().channels shouldContainExactlyInAnyOrder
                    listOf(ExternalChannel.SMS, ExternalChannel.EMAIL)
                ExternalNotification.smsOnly().channels shouldContainExactlyInAnyOrder listOf(ExternalChannel.SMS)
                ExternalNotification
                    .emailOnly(
                        emailTitle = SYNTHETIC_EMAIL_TITLE,
                        emailText = SYNTHETIC_EMAIL_TEXT,
                    ).channels shouldContainExactlyInAnyOrder listOf(ExternalChannel.EMAIL)
            }
        }

        context("brukervarselInactivate") {
            test("closes by reference, on the same partition key as the create") {
                val encoded =
                    Budstikka.brukervarselInactivate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                    )

                encoded.key shouldBe
                    Budstikka
                        .brukervarselCreate(
                            eventId = EVENT_ID,
                            reference = REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            varseltype = Varseltype.BESKJED,
                            text = SYNTHETIC_TEXT,
                        ).key
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrukervarselInactivate",""" +
                    """"referanse":"ref-1","sykmeldt":"00000000000"}}"""
            }

            context("dittSykefravaer") {
                test("create exposes the producer-supplied messageType through the public facade") {
                    val encoded =
                        Budstikka.dittSykefravaerCreate(
                            eventId = EVENT_ID,
                            reference = REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            text = SYNTHETIC_TEXT,
                            messageType = "DIALOGMOTE_INNKALLING",
                            link = "https://nav.no/syk",
                            visibleUntil = VISIBLE_UNTIL,
                        )

                    encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                    encoded.value shouldBe
                        """{"reference":"ref-1","content":{"type":"DittSykefravaerCreate",""" +
                        """"personIdentifier":"00000000000","text":"SYNTETISK-VARSELTEKST",""" +
                        """"messageType":"DIALOGMOTE_INNKALLING","link":"https://nav.no/syk",""" +
                        """"visibleUntil":"2026-01-01T00:00:00Z"}}"""
                }

                test("inactivate uses the same ingress partition key as create") {
                    val encoded =
                        Budstikka.dittSykefravaerInactivate(
                            eventId = EVENT_ID,
                            reference = REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                        )

                    encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                    encoded.value shouldBe
                        """{"reference":"ref-1","content":{"type":"DittSykefravaerInactivate",""" +
                        """"referanse":"ref-1","sykmeldt":"00000000000"}}"""
                }
            }
        }

        context("dineSykmeldteVarselCreate") {
            test("minimal call defaults the sending window to ONGOING and keys on the Sykmeldt") {
                val encoded =
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                    )

                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"LedervarselCreate",""" +
                    """"sykmeldt":"00000000000","orgnummer":"999999999","oppgavetype":"DIALOGMOTE_INNKALLING",""" +
                    """"text":"SYNTETISK-VARSELTEKST","visibleUntil":null,"sendingWindow":"ONGOING"}}"""
            }

            test("optional lifecycle fields are carried through unchanged") {
                val encoded =
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                        visibleUntil = VISIBLE_UNTIL,
                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                    )

                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"LedervarselCreate",""" +
                    """"sykmeldt":"00000000000","orgnummer":"999999999","oppgavetype":"DIALOGMOTE_INNKALLING",""" +
                    """"text":"SYNTETISK-VARSELTEKST","visibleUntil":"2026-01-01T00:00:00Z",""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }

            @Suppress("DEPRECATION")
            test("legacy positional null link remains source-compatible and is dropped") {
                val encoded =
                    Budstikka.dineSykmeldteVarselCreate(
                        EVENT_ID,
                        REFERENCE,
                        SYNTHETIC_SYKMELDT,
                        SYNTHETIC_ORGNUMMER,
                        Oppgavetype.DIALOGMOTE_INNKALLING,
                        SYNTHETIC_TEXT,
                        null,
                    )

                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"LedervarselCreate",""" +
                    """"sykmeldt":"00000000000","orgnummer":"999999999","oppgavetype":"DIALOGMOTE_INNKALLING",""" +
                    """"text":"SYNTETISK-VARSELTEKST","visibleUntil":null,"sendingWindow":"ONGOING"}}"""
            }

            test("encodes OPPFOLGINGSPLAN_PAAMINNELSE as its exact wire value") {
                val encoded =
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.OPPFOLGINGSPLAN_PAAMINNELSE,
                        text = SYNTHETIC_TEXT,
                    )

                encoded.value shouldContain """"oppgavetype":"OPPFOLGINGSPLAN_PAAMINNELSE""""
            }

            listOf(
                Oppgavetype.DIALOGMOTE_INNKALLING to "DIALOGMOTE_INNKALLING",
                Oppgavetype.DIALOGMOTE_ENDRING to "DIALOGMOTE_ENDRING",
                Oppgavetype.DIALOGMOTE_AVLYSNING to "DIALOGMOTE_AVLYSNING",
                Oppgavetype.DIALOGMOTE_REFERAT to "DIALOGMOTE_REFERAT",
            ).forEach { (oppgavetype, wireValue) ->
                test("encodes $oppgavetype as its exact wire value") {
                    val encoded =
                        Budstikka.dineSykmeldteVarselCreate(
                            eventId = EVENT_ID,
                            reference = REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            oppgavetype = oppgavetype,
                            text = SYNTHETIC_TEXT,
                        )

                    oppgavetype.wireValue shouldBe wireValue
                    encoded.value shouldContain """"oppgavetype":"$wireValue""""
                }
            }
        }

        context("dineSykmeldteVarselInactivate") {
            test("closes by reference and keys on the Sykmeldt, not the leader") {
                val encoded =
                    Budstikka.dineSykmeldteVarselInactivate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                    )

                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"LedervarselInactivate",""" +
                    """"referanse":"ref-1","sykmeldt":"00000000000"}}"""
            }
        }

        context("arbeidsgivervarselCreate") {
            test("Nærmeste leder encodes the existing wire format and keys on orgnummer") {
                val encoded =
                    Budstikka.arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient =
                            Arbeidsgivervarsel.NarmesteLeder(
                                SYNTHETIC_SYKMELDT,
                                Arbeidsgivervarsel.NarmesteLederExternalNotification("Tittel", "E-post"),
                            ),
                        tag = "Dialogmøte",
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                        messageType = Arbeidsgivervarsel.MessageType.OPPGAVE,
                        caseAssociation = Arbeidsgivervarsel.CaseAssociation("sak-1"),
                        visibleUntil = VISIBLE_UNTIL,
                        sendingWindow = SendingWindow.ONGOING,
                    )

                encoded.key shouldBe SYNTHETIC_ORGNUMMER.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"E-post"}},"tag":"Dialogmøte",""" +
                    """"text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/ag","meldingstype":"OPPGAVE",""" +
                    """"sakstilknytning":{"sakId":"sak-1"},"visibleUntil":"2026-01-01T00:00:00Z","sendingWindow":"ONGOING"}}"""
            }

            test("Altinn resource carries external notification fields and defaults") {
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
                        tag = "Oppfølging",
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"E-post","smsText":"SMS"}},""" +
                    """"tag":"Oppfølging","text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/ag",""" +
                    """"meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }

            test("Nærmeste leder HTML overload opts in without changing the legacy emailText field") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                        htmlEmail =
                            Arbeidsgivervarsel.HtmlEmailNotification(
                                emailTitle = "Tittel",
                                emailHtmlBody = "<p>E-post</p>",
                            ),
                        tag = "Oppfølging",
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","emailBodyFormat":"HTML"}},""" +
                    """"tag":"Oppfølging","text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/ag",""" +
                    """"meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }

            test("Altinn HTML overload carries HTML, SMS and the explicit format marker") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.AltinnResource("nav_syfo_dialogmote"),
                        htmlEmail = Arbeidsgivervarsel.HtmlEmailNotification("Tittel", "<p>E-post</p>"),
                        smsText = "SMS",
                        tag = "Oppfølging",
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","smsText":"SMS",""" +
                    """"emailBodyFormat":"HTML"}},"tag":"Oppfølging","text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/ag","meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }

            test("visibleUntil is encoded on the wire") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                        tag = "Dialogmøte",
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                        visibleUntil = VISIBLE_UNTIL,
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":null},"tag":"Dialogmøte","text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/ag","meldingstype":"BESKJED","sakstilknytning":null,""" +
                    """"visibleUntil":"2026-01-01T00:00:00Z","sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
            }
        }

        context("brevCreate") {
            test("defaults to IMPORTANT and the ordinary dokdist route") {
                val encoded =
                    Budstikka.brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                    )

                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrevCreate",""" +
                    """"personIdentifier":"00000000000","journalpostId":"jp-1",""" +
                    """"distributionType":"IMPORTANT","tvingSentralPrint":false}}"""
            }

            test("an explicit distribution type overrides the default") {
                Budstikka
                    .brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                        distributionType = DistributionType.OTHER,
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrevCreate",""" +
                    """"personIdentifier":"00000000000","journalpostId":"jp-1",""" +
                    """"distributionType":"OTHER","tvingSentralPrint":false}}"""
            }

            test("tvingSentralPrint opts in to forced paper delivery") {
                Budstikka
                    .brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                        tvingSentralPrint = true,
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrevCreate",""" +
                    """"personIdentifier":"00000000000","journalpostId":"jp-1",""" +
                    """"distributionType":"IMPORTANT","tvingSentralPrint":true}}"""
            }
        }

        context("microfrontend") {
            test("enable carries the optional visibility limit") {
                val visibleUntil = Clock.System.now() + 30.days
                val encoded =
                    Budstikka.microfrontendEnable(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "syk-dialog",
                        visibleUntil = visibleUntil,
                    )

                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"MicrofrontendEnable",""" +
                    """"personIdentifier":"00000000000","microfrontendId":"syk-dialog",""" +
                    """"visibleUntil":"$visibleUntil"}}"""
            }

            test("enable accepts a visibility limit up to 335 days ahead") {
                Budstikka
                    .microfrontendEnable(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "syk-dialog",
                        visibleUntil = Clock.System.now() + 334.days,
                    ).value shouldContain "\"visibleUntil\":"
            }

            test("enable rejects a visibility limit in the past or more than 335 days ahead") {
                listOf(Clock.System.now() - 1.days, Clock.System.now() + 336.days).forEach { visibleUntil ->
                    shouldThrow<IllegalArgumentException> {
                        Budstikka.microfrontendEnable(
                            eventId = EVENT_ID,
                            reference = REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            microfrontendId = "syk-dialog",
                            visibleUntil = visibleUntil,
                        )
                    }.message shouldContain "visibleUntil"
                }
            }

            test("enable without a visibility limit stays visible until disabled") {
                Budstikka
                    .microfrontendEnable(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "syk-dialog",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"MicrofrontendEnable",""" +
                    """"personIdentifier":"00000000000","microfrontendId":"syk-dialog","visibleUntil":null}}"""
            }

            test("disable is its own variant, not an inactivate") {
                Budstikka
                    .microfrontendDisable(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "syk-dialog",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"MicrofrontendDisable",""" +
                    """"personIdentifier":"00000000000","microfrontendId":"syk-dialog"}}"""
            }
        }

        context("arbeidsgiversak") {
            test("a notification to Nærmeste leder carries the saktype instead of a tag") {
                val encoded =
                    Budstikka.arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                        sendingWindow = SendingWindow.ONGOING,
                    )

                encoded.key shouldBe SYNTHETIC_ORGNUMMER.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":null},"text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/ag",""" +
                    """"meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,"sendingWindow":"ONGOING",""" +
                    """"saktype":"DIALOGMOTE"}}"""
            }

            test("a notification to an Altinn resource carries the Sykmeldt its case is about") {
                Budstikka
                    .arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = Arbeidsgivervarsel.AltinnResource("nav_syfo_dialogmote"),
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        htmlEmail = Arbeidsgivervarsel.HtmlEmailNotification("Tittel", "<p>E-post</p>"),
                        smsText = "SMS",
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/ag",
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"ArbeidsgivervarselCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","smsText":"SMS",""" +
                    """"emailBodyFormat":"HTML"},"sykmeldt":"00000000000"},"text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/ag","meldingstype":"BESKJED","sakstilknytning":null,"visibleUntil":null,""" +
                    """"sendingWindow":"BUDSTIKKA_OPENING_HOURS","saktype":"DIALOGMOTE"}}"""
            }

            listOf(
                Arbeidsgiversak.Type.DIALOGMOTE to "DIALOGMOTE",
                Arbeidsgiversak.Type.OPPFOLGINGSPLAN to "OPPFOLGINGSPLAN",
            ).also { pairs -> pairs.map { it.first }.toSet() shouldBe Arbeidsgiversak.Type.entries.toSet() }
                .forEach { (saktype, wireValue) ->
                    test("saktype $saktype maps to $wireValue") {
                        Budstikka
                            .arbeidsgivervarselCreate(
                                eventId = EVENT_ID,
                                reference = REFERENCE,
                                orgnummer = SYNTHETIC_ORGNUMMER,
                                recipient = Arbeidsgivervarsel.NarmesteLeder(SYNTHETIC_SYKMELDT),
                                saktype = saktype,
                                text = SYNTHETIC_TEXT,
                                link = "https://nav.no/ag",
                            ).value shouldContain """"saktype":"$wireValue""""
                    }
                }
        }

        context("kalenderavtale") {
            test("create keys on orgnummer and carries saktype, leader, time, place and HTML email") {
                val encoded =
                    Budstikka.kalenderavtaleCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = "Dialogmøte med Test",
                        link = "https://nav.no/ag",
                        startAt = Instant.parse("2026-11-02T09:00:00Z"),
                        endAt = Instant.parse("2026-11-02T10:00:00Z"),
                        lokasjon = Kalenderavtale.Lokasjon("Gate 1", "0001", "Oslo"),
                        digital = true,
                        email = Kalenderavtale.EmailNotification.html("Tittel", "<p>E-post</p>"),
                    )

                encoded.key shouldBe SYNTHETIC_ORGNUMMER.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"KalenderavtaleCreate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"<p>E-post</p>","emailBodyFormat":"HTML"}},""" +
                    """"saktype":"DIALOGMOTE","text":"Dialogmøte med Test","link":"https://nav.no/ag","startAt":"2026-11-02T09:00:00Z",""" +
                    """"endAt":"2026-11-02T10:00:00Z","lokasjon":{"adresse":"Gate 1","postnummer":"0001",""" +
                    """"poststed":"Oslo"},"digital":true,"tilstand":"VENTER_SVAR_FRA_ARBEIDSGIVER"}}"""
            }

            test("update carries only the requested changes and a plain-text email") {
                Budstikka
                    .kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        tilstand = Kalenderavtale.Tilstand.AVLYST,
                        email = Kalenderavtale.EmailNotification.plainText("Tittel", "E-post"),
                    ).value shouldBe
                    """{"reference":"ref-1","content":{"type":"KalenderavtaleUpdate",""" +
                    """"orgnummer":"999999999","mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""" +
                    """"externalVarsling":{"emailTitle":"Tittel","emailText":"E-post"}},"saktype":"DIALOGMOTE",""" +
                    """"tilstand":"AVLYST","text":null}}"""
            }

            listOf(
                Kalenderavtale.Tilstand.VENTER_SVAR_FRA_ARBEIDSGIVER to "VENTER_SVAR_FRA_ARBEIDSGIVER",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_VIL_AVLYSE to "ARBEIDSGIVER_VIL_AVLYSE",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED to "ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED",
                Kalenderavtale.Tilstand.ARBEIDSGIVER_HAR_GODTATT to "ARBEIDSGIVER_HAR_GODTATT",
                Kalenderavtale.Tilstand.AVLYST to "AVLYST",
                Kalenderavtale.Tilstand.AVHOLDT to "AVHOLDT",
            ).also { pairs -> pairs.map { it.first }.toSet() shouldBe Kalenderavtale.Tilstand.entries.toSet() }
                .forEach { (tilstand, wireValue) ->
                    test("tilstand $tilstand maps to $wireValue") {
                        Budstikka
                            .kalenderavtaleUpdate(
                                eventId = EVENT_ID,
                                reference = REFERENCE,
                                orgnummer = SYNTHETIC_ORGNUMMER,
                                sykmeldt = SYNTHETIC_SYKMELDT,
                                saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                                tilstand = tilstand,
                            ).value shouldContain """"tilstand":"$wireValue""""
                    }
                }

            test("text is limited to 300 characters on create and update") {
                fun create(text: String) =
                    Budstikka.kalenderavtaleCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = text,
                        link = "https://nav.no/ag",
                        startAt = Instant.parse("2026-11-02T09:00:00Z"),
                    )
                create("x".repeat(300)).value shouldContain "\"text\":"
                shouldThrow<IllegalArgumentException> { create("x".repeat(301)) }.message shouldBe
                    "text must be at most 300 characters"
                shouldThrow<IllegalArgumentException> {
                    Budstikka.kalenderavtaleUpdate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        saktype = Arbeidsgiversak.Type.DIALOGMOTE,
                        text = "x".repeat(301),
                    )
                }.message shouldBe "text must be at most 300 characters"
            }
        }

        context("the record itself") {
            test("eventId is only a header, never part of the payload") {
                val encoded =
                    Budstikka.brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                    )

                encoded.headers.keys shouldBe setOf("eventId")
                encoded.value.contains("eventId") shouldBe false
                encoded.value.contains(EVENT_ID.value.toString()) shouldBe false
            }

            test("a caller-supplied eventId is used verbatim, so a retry deduplicates") {
                val retried =
                    Budstikka.brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                    )

                retried.eventId shouldBe EVENT_ID
                retried.headers["eventId"] shouldBe EVENT_ID.value.toString()
            }

            test("EventId.new returns a fresh id each time") {
                EventId.new() shouldNotBe EventId.new()
            }

            test("headerBytes gives Kafka-ready UTF-8 without a kafka-clients dependency") {
                val encoded =
                    Budstikka.brevCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "jp-1",
                    )

                encoded.headerBytes().mapValues { (_, bytes) -> String(bytes, Charsets.UTF_8) } shouldBe encoded.headers
            }

            test("the encoded payload decodes back into the same content, for the consuming side") {
                val encoded =
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.OPPGAVE,
                        text = SYNTHETIC_TEXT,
                        externalVarsling = ExternalNotification.smsAndEmail(smsText = SYNTHETIC_SMS_TEXT),
                    )

                // Decoding is the consuming side of the contract and lives behind the wire opt-in, so the
                // assertion on the decoded envelope is in DispatchSerializationTest. Here we stay on the
                // producer side: this whole file must keep compiling without opting in.
                encoded.value shouldContain "\"type\":\"BrukervarselCreate\""
            }
        }

        test("only variants budstikka delivers end to end are exposed as sendable") {
            // Value-class parameters make the JVM mangle each name (`brukervarselCreate-F3-nEFM`);
            // the Kotlin name is the part before the hash.
            Budstikka::class.java.declaredMethods
                .map { it.name.substringBefore('-') }
                .filterNot { it.contains('$') }
                .distinct() shouldContainExactlyInAnyOrder
                listOf(
                    "brukervarselCreate",
                    "brukervarselInactivate",
                    "dittSykefravaerCreate",
                    "dittSykefravaerInactivate",
                    "dineSykmeldteVarselCreate",
                    "dineSykmeldteVarselInactivate",
                    "arbeidsgivervarselCreate",
                    "brevCreate",
                    "microfrontendEnable",
                    "microfrontendDisable",
                    "kalenderavtaleCreate",
                    "kalenderavtaleUpdate",
                )
        }
    })
