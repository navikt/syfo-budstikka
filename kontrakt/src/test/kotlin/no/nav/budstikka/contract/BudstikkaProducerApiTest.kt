package no.nav.budstikka.contract

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.util.UUID
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
                    """"personIdentifier":"11111111111","varseltype":"BESKJED","text":"SYNTETISK-VARSELTEKST",""" +
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
                        externalVarsling = ExternalVarsling.smsOnly(smsText = SYNTHETIC_SMS_TEXT),
                        brevFallback = BrevFallback(journalpostId = "jp-1"),
                        sendingWindow = SendingWindow.ONGOING,
                    )

                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"BrukervarselCreate",""" +
                    """"personIdentifier":"11111111111","varseltype":"OPPGAVE","text":"SYNTETISK-VARSELTEKST",""" +
                    """"link":"https://nav.no/syk","visibleUntil":"2026-01-01T00:00:00Z",""" +
                    """"externalVarsling":{"channels":["SMS"],"smsText":"SYNTETISK-SMSTEKST",""" +
                    """"emailTitle":null,"emailText":null},""" +
                    """"brevFallback":{"journalpostId":"jp-1","distributionType":"IMPORTANT"},""" +
                    """"sendingWindow":"ONGOING"}}"""
            }

            test("external varsling factories set exactly the channels they name") {
                ExternalVarsling.smsAndEmail().channels shouldContainExactlyInAnyOrder
                    listOf(ExternalChannel.SMS, ExternalChannel.EMAIL)
                ExternalVarsling.smsOnly().channels shouldContainExactlyInAnyOrder listOf(ExternalChannel.SMS)
                ExternalVarsling
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
                    """"referanse":"ref-1","sykmeldt":"11111111111"}}"""
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
                    """"sykmeldt":"11111111111","orgnummer":"999999999","oppgavetype":"DIALOGMOTE_INNKALLING",""" +
                    """"text":"SYNTETISK-VARSELTEKST","link":null,"visibleUntil":null,"sendingWindow":"ONGOING"}}"""
            }

            test("optional fields are carried through unchanged") {
                val encoded =
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                        link = "https://nav.no/dine-sykmeldte",
                        visibleUntil = VISIBLE_UNTIL,
                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                    )

                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"LedervarselCreate",""" +
                    """"sykmeldt":"11111111111","orgnummer":"999999999","oppgavetype":"DIALOGMOTE_INNKALLING",""" +
                    """"text":"SYNTETISK-VARSELTEKST","link":"https://nav.no/dine-sykmeldte",""" +
                    """"visibleUntil":"2026-01-01T00:00:00Z","sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
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
                    """"referanse":"ref-1","sykmeldt":"11111111111"}}"""
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
                    """"personIdentifier":"11111111111","journalpostId":"jp-1",""" +
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
                    """"personIdentifier":"11111111111","journalpostId":"jp-1",""" +
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
                    """"personIdentifier":"11111111111","journalpostId":"jp-1",""" +
                    """"distributionType":"IMPORTANT","tvingSentralPrint":true}}"""
            }
        }

        context("microfrontend") {
            test("enable carries the optional visibility limit") {
                val encoded =
                    Budstikka.microfrontendEnable(
                        eventId = EVENT_ID,
                        reference = REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "syk-dialog",
                        visibleUntil = VISIBLE_UNTIL,
                    )

                encoded.key shouldBe SYNTHETIC_SYKMELDT.value
                encoded.value shouldBe
                    """{"reference":"ref-1","content":{"type":"MicrofrontendEnable",""" +
                    """"personIdentifier":"11111111111","microfrontendId":"syk-dialog",""" +
                    """"visibleUntil":"2026-01-01T00:00:00Z"}}"""
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
                    """"personIdentifier":"11111111111","microfrontendId":"syk-dialog","visibleUntil":null}}"""
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
                    """"personIdentifier":"11111111111","microfrontendId":"syk-dialog"}}"""
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
                        externalVarsling = ExternalVarsling.smsAndEmail(smsText = SYNTHETIC_SMS_TEXT),
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
                    "dineSykmeldteVarselCreate",
                    "dineSykmeldteVarselInactivate",
                    "brevCreate",
                    "microfrontendEnable",
                    "microfrontendDisable",
                )
        }
    })
