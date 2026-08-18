// This suite asserts the privacy behaviour of the whole contract surface — including the raw wire
// DTOs. Testing the engine room is exactly what the opt-in is for.
@file:OptIn(InternalBudstikkaWire::class)

package no.nav.budstikka.contract

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.time.Instant

private val EVENT_ID = EventId(UUID.fromString("00000000-0000-4000-8000-0000000000a2"))

/**
 * The contract carries person data, ids that point at a person's case or document, and text written
 * for a person. Nothing here may reach a log line by accident, so neither `toString` nor a validation
 * failure is allowed to reproduce an identifier, a reference, a downstream id, a link, a notification
 * text or a raw payload. Values below are deliberately synthetic and unmistakable: if one of them
 * shows up in a string, that string leaks.
 * The eventId remains visible because it is the technical correlation key.
 */
class ContractPrivacyTest :
    FunSpec({
        val secrets =
            listOf(
                SYNTHETIC_SYKMELDT.value,
                SYNTHETIC_ORGNUMMER.value,
                SYNTHETIC_TEXT,
                SYNTHETIC_SMS_TEXT,
                SYNTHETIC_EMAIL_TITLE,
                SYNTHETIC_EMAIL_TEXT,
                SYNTHETIC_REFERENCE,
                SYNTHETIC_JOURNALPOST_ID,
                SYNTHETIC_SAK_ID,
                SYNTHETIC_MICROFRONTEND_ID,
                SYNTHETIC_LINK,
            )

        fun String.shouldNotLeak() = secrets.forEach { secret -> this shouldNotContain secret }

        val externalVarsling =
            ExternalNotification.smsAndEmail(
                smsText = SYNTHETIC_SMS_TEXT,
                emailTitle = SYNTHETIC_EMAIL_TITLE,
                emailText = SYNTHETIC_EMAIL_TEXT,
            )
        val brevFallback = BrevFallback(journalpostId = SYNTHETIC_JOURNALPOST_ID)
        val sakstilknytning = Sakstilknytning(sakId = SYNTHETIC_SAK_ID)
        val narmesteLederExternalVarsling =
            NarmesteLederExternalVarsling(
                emailTitle = SYNTHETIC_EMAIL_TITLE,
                emailText = SYNTHETIC_EMAIL_TEXT,
            )
        val altinnExternalVarsling =
            AltinnExternalVarsling(
                emailTitle = SYNTHETIC_EMAIL_TITLE,
                emailText = SYNTHETIC_EMAIL_TEXT,
                smsText = SYNTHETIC_SMS_TEXT,
            )

        context("identifiers mask themselves") {
            test("PersonIdentifier") {
                SYNTHETIC_SYKMELDT.toString() shouldBe "***"
            }

            test("Orgnummer") {
                SYNTHETIC_ORGNUMMER.toString() shouldBe "***"
            }
        }

        context("toString of a wire variant leaks neither identifier, reference, id, link, text nor payload") {
            val variants: List<DispatchContent> =
                listOf(
                    BrukervarselCreate(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.OPPGAVE,
                        text = SYNTHETIC_TEXT,
                        link = SYNTHETIC_LINK,
                        visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                        externalVarsling = externalVarsling,
                        brevFallback = brevFallback,
                    ),
                    BrukervarselInactivate(reference = SYNTHETIC_REFERENCE, sykmeldt = SYNTHETIC_SYKMELDT),
                    LedervarselCreate(
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                        link = SYNTHETIC_LINK,
                        visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                    LedervarselInactivate(reference = SYNTHETIC_REFERENCE, sykmeldt = SYNTHETIC_SYKMELDT),
                    DittSykefravaerCreate(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        text = SYNTHETIC_TEXT,
                        link = SYNTHETIC_LINK,
                    ),
                    DittSykefravaerInactivate(reference = SYNTHETIC_REFERENCE, sykmeldt = SYNTHETIC_SYKMELDT),
                    ArbeidsgivervarselCreate(
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient =
                            NarmesteLeder(
                                sykmeldt = SYNTHETIC_SYKMELDT,
                                externalVarsling = narmesteLederExternalVarsling,
                            ),
                        tag = Tag.DIALOGMOETE,
                        text = SYNTHETIC_TEXT,
                        link = SYNTHETIC_LINK,
                        sakstilknytning = sakstilknytning,
                    ),
                    ArbeidsgivervarselInactivate(reference = SYNTHETIC_REFERENCE, orgnummer = SYNTHETIC_ORGNUMMER),
                    BrevCreate(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        journalpostId = SYNTHETIC_JOURNALPOST_ID,
                    ),
                    MicrofrontendEnable(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        microfrontendId = SYNTHETIC_MICROFRONTEND_ID,
                        visibleUntil = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                    MicrofrontendDisable(
                        personIdentifier = SYNTHETIC_SYKMELDT,
                        microfrontendId = SYNTHETIC_MICROFRONTEND_ID,
                    ),
                )

            variants.forEach { content ->
                test("${content::class.simpleName} and its envelope stay clean") {
                    content.toString().shouldNotLeak()
                    content.toString() shouldContain content::class.simpleName!!
                    Dispatch(reference = SYNTHETIC_REFERENCE, content = content).toString().shouldNotLeak()
                }
            }

            // The suite is only worth its name while it covers everything on the wire, so the sealed
            // hierarchy itself decides the list: a new variant fails here until it is added above.
            test("every variant of the sealed contract is covered") {
                variants.map { it::class }.toSet() shouldBe DispatchContent::class.concreteVariants()
            }
        }

        context("toString of an identifier-bearing helper type stays clean") {
            listOf<Pair<String, Any>>(
                "ExternalNotification" to externalVarsling,
                "NarmesteLederExternalVarsling" to narmesteLederExternalVarsling,
                "AltinnExternalVarsling" to altinnExternalVarsling,
                "BrevFallback" to brevFallback,
                "Sakstilknytning" to sakstilknytning,
                "NarmesteLeder" to
                    NarmesteLeder(
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        externalVarsling = narmesteLederExternalVarsling,
                    ),
                "AltinnResource" to
                    AltinnResource(
                        resource = AltinnResourceId.DIALOGMOETE,
                        externalVarsling = altinnExternalVarsling,
                    ),
            ).forEach { (name, value) ->
                test(name) {
                    value.toString().shouldNotLeak()
                }
            }
        }

        context("EncodedDispatch") {
            val encoded: List<Pair<String, EncodedDispatch>> =
                listOf(
                    "brukervarselCreate" to
                        Budstikka.brukervarselCreate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            varseltype = Varseltype.BESKJED,
                            text = SYNTHETIC_TEXT,
                            link = SYNTHETIC_LINK,
                            externalVarsling = externalVarsling,
                            brevFallback = brevFallback,
                        ),
                    "brukervarselInactivate" to
                        Budstikka.brukervarselInactivate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                        ),
                    "dineSykmeldteVarselCreate" to
                        Budstikka.dineSykmeldteVarselCreate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                            text = SYNTHETIC_TEXT,
                            link = SYNTHETIC_LINK,
                        ),
                    "dineSykmeldteVarselInactivate" to
                        Budstikka.dineSykmeldteVarselInactivate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                        ),
                    "arbeidsgivervarselCreate" to
                        Budstikka.arbeidsgivervarselCreate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            orgnummer = SYNTHETIC_ORGNUMMER,
                            recipient =
                                Arbeidsgivervarsel.NarmesteLeder(
                                    sykmeldt = SYNTHETIC_SYKMELDT,
                                    externalVarsling =
                                        Arbeidsgivervarsel.NarmesteLederExternalVarsling(
                                            emailTitle = SYNTHETIC_EMAIL_TITLE,
                                            emailText = SYNTHETIC_EMAIL_TEXT,
                                        ),
                                ),
                            tag = Arbeidsgivervarsel.Tag.DIALOGMOETE,
                            text = SYNTHETIC_TEXT,
                            link = SYNTHETIC_LINK,
                        ),
                    "brevCreate" to
                        Budstikka.brevCreate(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            journalpostId = SYNTHETIC_JOURNALPOST_ID,
                        ),
                    "microfrontendEnable" to
                        Budstikka.microfrontendEnable(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            microfrontendId = SYNTHETIC_MICROFRONTEND_ID,
                        ),
                    "microfrontendDisable" to
                        Budstikka.microfrontendDisable(
                            eventId = EVENT_ID,
                            reference = SYNTHETIC_REFERENCE,
                            sykmeldt = SYNTHETIC_SYKMELDT,
                            microfrontendId = SYNTHETIC_MICROFRONTEND_ID,
                        ),
                )

            encoded.forEach { (name, dispatch) ->
                test("$name exposes neither key, payload, reference nor free text") {
                    dispatch.toString().shouldNotLeak()
                    dispatch.toString() shouldContain Budstikka.TOPIC
                    dispatch.toString() shouldContain EVENT_ID.value.toString()
                }
            }

            test("the payload itself still carries the values, since delivery needs them") {
                val brukervarsel = encoded.first().second
                brukervarsel.key shouldBe SYNTHETIC_SYKMELDT.value
                brukervarsel.value shouldContain SYNTHETIC_TEXT
                brukervarsel.value shouldContain SYNTHETIC_REFERENCE
            }
        }

        context("Arbeidsgivervarsel facade") {
            val narmesteLeder =
                Arbeidsgivervarsel.NarmesteLeder(
                    SYNTHETIC_SYKMELDT,
                    Arbeidsgivervarsel.NarmesteLederExternalVarsling(SYNTHETIC_EMAIL_TITLE, SYNTHETIC_EMAIL_TEXT),
                )
            val altinn =
                Arbeidsgivervarsel.AltinnRessurs(
                    Arbeidsgivervarsel.Ressurs.DIALOGMOETE,
                    Arbeidsgivervarsel.AltinnExternalVarsling(
                        SYNTHETIC_EMAIL_TITLE,
                        SYNTHETIC_EMAIL_TEXT,
                        SYNTHETIC_SMS_TEXT,
                    ),
                )

            test("producer-facing inputs do not leak person data or text") {
                listOf<Any>(
                    narmesteLeder,
                    altinn,
                    narmesteLeder.externalVarsling!!,
                    altinn.externalVarsling!!,
                    Arbeidsgivervarsel.Sakstilknytning(SYNTHETIC_SAK_ID),
                ).forEach { it.toString().shouldNotLeak() }
            }

            test("validation failures name only the parameter") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        " ",
                        SYNTHETIC_ORGNUMMER,
                        narmesteLeder,
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "reference" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        Orgnummer("12"),
                        narmesteLeder,
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "orgnummer" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        narmesteLeder,
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        " ",
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "text" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.NarmesteLeder(PersonIdentifier("1234")),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.sykmeldt" }.also { it shouldNotContain "1234" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        altinn,
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        " ",
                    )
                }.message!!.also { it shouldContain "link" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.AltinnRessurs(
                            Arbeidsgivervarsel.Ressurs.DIALOGMOETE,
                            Arbeidsgivervarsel.AltinnExternalVarsling(" ", SYNTHETIC_EMAIL_TEXT, SYNTHETIC_SMS_TEXT),
                        ),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.externalVarsling.emailTitle" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.NarmesteLeder(
                            SYNTHETIC_SYKMELDT,
                            Arbeidsgivervarsel.NarmesteLederExternalVarsling(SYNTHETIC_EMAIL_TITLE, " "),
                        ),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.externalVarsling.emailText" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.NarmesteLeder(
                            SYNTHETIC_SYKMELDT,
                            Arbeidsgivervarsel.NarmesteLederExternalVarsling(" ", SYNTHETIC_EMAIL_TEXT),
                        ),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.externalVarsling.emailTitle" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.AltinnRessurs(
                            Arbeidsgivervarsel.Ressurs.DIALOGMOETE,
                            Arbeidsgivervarsel.AltinnExternalVarsling(SYNTHETIC_EMAIL_TITLE, " ", SYNTHETIC_SMS_TEXT),
                        ),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.externalVarsling.emailText" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        Arbeidsgivervarsel.AltinnRessurs(
                            Arbeidsgivervarsel.Ressurs.DIALOGMOETE,
                            Arbeidsgivervarsel.AltinnExternalVarsling(SYNTHETIC_EMAIL_TITLE, SYNTHETIC_EMAIL_TEXT, " "),
                        ),
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                    )
                }.message!!.also { it shouldContain "recipient.externalVarsling.smsText" }.shouldNotLeak()

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        EVENT_ID,
                        SYNTHETIC_REFERENCE,
                        SYNTHETIC_ORGNUMMER,
                        narmesteLeder,
                        Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        SYNTHETIC_TEXT,
                        SYNTHETIC_LINK,
                        sakstilknytning = Arbeidsgivervarsel.Sakstilknytning(" "),
                    )
                }.message!!.also { it shouldContain "sakstilknytning.sakId" }.shouldNotLeak()
            }

            test("visibleUntil is unsupported and its value stays private") {
                val visibleUntil = Instant.parse("2026-02-03T04:05:06Z")

                shouldThrow<IllegalArgumentException> {
                    Budstikka.arbeidsgivervarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        recipient = narmesteLeder,
                        tag = Arbeidsgivervarsel.Tag.DIALOGMOETE,
                        text = SYNTHETIC_TEXT,
                        link = SYNTHETIC_LINK,
                        visibleUntil = visibleUntil,
                    )
                }.message!!
                    .also { it shouldContain "visibleUntil" }
                    .also { it shouldContain "not supported for arbeidsgivervarselCreate" }
                    .also { it shouldNotContain visibleUntil.toString() }
                    .shouldNotLeak()
            }
        }

        context("validation failures name the parameter, never its value") {
            test("blank reference") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = "  ",
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.BESKJED,
                        text = SYNTHETIC_TEXT,
                    )
                }.message!!.also { it shouldContain "reference" }.shouldNotLeak()
            }

            test("blank text") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.BESKJED,
                        text = "",
                    )
                }.message!!.also { it shouldContain "text" }.shouldNotLeak()
            }

            test("personident that is not 11 digits") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = PersonIdentifier("1234"),
                        varseltype = Varseltype.BESKJED,
                        text = SYNTHETIC_TEXT,
                    )
                }.message!!
                    .also { it shouldContain "sykmeldt" }
                    .also { it shouldNotContain "1234" }
                    .shouldNotLeak()
            }

            test("orgnummer that is not 9 digits") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = Orgnummer("12"),
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                    )
                }.message!!
                    .also { it shouldContain "orgnummer" }
                    .also { it shouldNotContain "12" }
                    .shouldNotLeak()
            }

            test("blank link when a link is given") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.dineSykmeldteVarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        orgnummer = SYNTHETIC_ORGNUMMER,
                        oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
                        text = SYNTHETIC_TEXT,
                        link = " ",
                    )
                }.message!!.also { it shouldContain "link" }.shouldNotLeak()
            }

            test("blank journalpostId") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.brevCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        journalpostId = "",
                    )
                }.message!!.also { it shouldContain "journalpostId" }.shouldNotLeak()
            }

            test("blank journalpostId in brevFallback") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.brukervarselCreate(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        varseltype = Varseltype.BESKJED,
                        text = SYNTHETIC_TEXT,
                        brevFallback = BrevFallback(journalpostId = " "),
                    )
                }.message!!.also { it shouldContain "brevFallback.journalpostId" }.shouldNotLeak()
            }

            test("blank microfrontendId") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.microfrontendEnable(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = " ",
                    )
                }.message!!.also { it shouldContain "microfrontendId" }.shouldNotLeak()
            }

            test("blank microfrontendId on disable") {
                shouldThrow<IllegalArgumentException> {
                    Budstikka.microfrontendDisable(
                        eventId = EVENT_ID,
                        reference = SYNTHETIC_REFERENCE,
                        sykmeldt = SYNTHETIC_SYKMELDT,
                        microfrontendId = "",
                    )
                }.message!!.also { it shouldContain "microfrontendId" }.shouldNotLeak()
            }
        }
    })

/** Every concrete variant under a sealed contract type, found through the hierarchy, not a hand-list. */
private fun KClass<out DispatchContent>.concreteVariants(): Set<KClass<out DispatchContent>> =
    if (isSealed) sealedSubclasses.flatMap { it.concreteVariants() }.toSet() else setOf(this)
