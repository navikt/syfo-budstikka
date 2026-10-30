package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.EmailBodyFormat
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Saktype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.FakePersonnavnLookup
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant
import no.nav.budstikka.contract.NarmesteLederExternalVarsling as NarmesteLederExternalVarslingWire

private val LEADER = PersonIdentifier("00000000000")
private const val EVENT_ID = "00000000-0000-0000-0000-000000000902"
private val PLAIN_EMAIL = NarmesteLederExternalVarslingWire("Tittel", "A & B")

class KalenderavtaleChannelHandlerTest :
    FunSpec({
        test("creates the event in the leader relation's case with the dispatch reference as eksternId") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Sent

            publisher.calendarCreates.single() shouldBe
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    eksternId = "avtale-1",
                    tekst = "Dialogmøte med Test",
                    lenke = "https://nav.no/ag",
                    startAt = Instant.parse("2026-11-02T09:00:00Z"),
                    endAt = null,
                    lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
                    erDigitalt = false,
                    tilstand = Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER,
                    recipient =
                        ArbeidsgiverNotificationRecipient.NarmesteLeder(
                            narmesteLederFnr = LEADER,
                            ansattFnr = TEST_SYKMELDT,
                            externalVarsling =
                                NarmesteLederExternalVarsling(
                                    epostTittel = "Tittel",
                                    epostHtmlBody = "A &amp; B",
                                    epostadresser = listOf("leder@example.test"),
                                ),
                        ),
                    correlationId = EVENT_ID,
                )
        }

        test("extends the leader relation's case to four months past the event before creating it") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Sent

            publisher.requests.first() shouldBe
                ArbeidsgiversakExtendRequest(
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    keepUntil = Instant.parse("2027-03-02T09:00:00Z"),
                    correlationId = EVENT_ID,
                )
        }

        test("sets the requested case status after creating the event") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(
                delivery(create().copy(saksstatus = Saksstatus.UNDER_BEHANDLING)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.last() shouldBe
                ArbeidsgiversakStatusRequest(
                    "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    "Dialogmøte",
                    Saksstatus.UNDER_BEHANDLING,
                    EVENT_ID,
                )
        }

        test("an update with a status sets it on the current leader relation's case") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, saksstatus = Saksstatus.FERDIG)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.calendarUpdates.single().nyTilstand shouldBe Kalenderavtaletilstand.AVLYST
            publisher.caseStatuses.single().apply {
                grupperingsid shouldBe "DIALOGMOTE:$TEST_LEDERRELASJON_ID"
                status shouldBe Saksstatus.FERDIG
            }
        }

        test("forwards explicit HTML email unchanged") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(
                delivery(create(email = NarmesteLederExternalVarslingWire("Tittel", "<p>Hei</p>", EmailBodyFormat.HTML))),
            )

            publisher.calendarCreates
                .single()
                .recipient.externalVarsling
                ?.epostHtmlBody shouldBe "<p>Hei</p>"
        }

        test("fails terminally when an email is requested but the leader has no address") {
            val publisher = FakeArbeidsgiversakPublisher()
            val lookup =
                FakeNarmesteLederLookup().apply {
                    registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, emptyList(), TEST_LEDERRELASJON_ID))
                }

            handler(publisher, lookup).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE NarmesteLeder delivery unavailable: missing_email_address")
            publisher.requests shouldBe emptyList()
        }

        test("fails terminally and meters a missing leader") {
            val metrics = RecordingDeliveryMetrics()

            handler(FakeArbeidsgiversakPublisher(), metrics = metrics).handle(delivery(create())) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE NarmesteLeder delivery unavailable: missing_active_leader")
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
        }

        test("does not support an Altinn recipient") {
            handler(FakeArbeidsgiversakPublisher()).handle(
                delivery(create(recipient = AltinnResource("nav_syfo_dialogmote"))),
            ) shouldBe DeliveryOutcome.Failed("KALENDERAVTALE supports only a NarmesteLeder recipient")
        }

        test("retries a create while the ensured case is not visible yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )

            shouldThrow<ChannelHandlerFailure> {
                handler(publisher, leaderRegistered()).handle(delivery(create()))
            }
        }

        test("stops before the event when the case cannot be ensured") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))

            handler(publisher, leaderRegistered()).handle(delivery(create())) shouldBe
                DeliveryOutcome.Failed("UgyldigMerkelapp")
            publisher.calendarCreates shouldBe emptyList()
        }

        test("updates the state without a leader lookup when no email is requested") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(update(tilstand = Kalenderavtaletilstand.AVLYST))) shouldBe
                DeliveryOutcome.Sent

            publisher.calendarUpdates.single() shouldBe
                KalenderavtaleUpdateRequest(
                    tag = "Dialogmøte",
                    eksternId = "avtale-1",
                    nyTilstand = Kalenderavtaletilstand.AVLYST,
                    nyTekst = null,
                    externalVarsling = null,
                    idempotencyKey = EVENT_ID,
                )
        }

        test("resolves the leader's addresses when an update requests an email") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.calendarUpdates
                .single()
                .externalVarsling
                ?.epostadresser shouldBe listOf("leder@example.test")
        }

        test("retries an update while the event does not exist yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.NotYetAvailable("NotifikasjonFinnesIkke"))

            shouldThrow<ChannelHandlerFailure> {
                handler(publisher).handle(delivery(update(tilstand = Kalenderavtaletilstand.AVLYST)))
            }
        }

        test("fails texts above Fager's limit before publishing") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(delivery(create().copy(text = "x".repeat(301)))) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE text must be at most 300 characters")
            handler(publisher).handle(delivery(update().copy(text = "x".repeat(301)))) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE text must be at most 300 characters")
            publisher.requests shouldBe emptyList()
        }

        test("fails an update without any change") {
            handler(FakeArbeidsgiversakPublisher()).handle(delivery(update())) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE update has no change")
        }
    })

private fun handler(
    publisher: FakeArbeidsgiversakPublisher,
    lookup: NarmesteLederLookup = FakeNarmesteLederLookup(),
    metrics: RecordingDeliveryMetrics = RecordingDeliveryMetrics(),
) = KalenderavtaleChannelHandler(
    publisher,
    Arbeidsgiversaker(
        publisher,
        FakePersonnavnLookup(),
        ArbeidsgiversakConfig("https://dinesykmeldte.test", "syntetisk-pseudonymnokkel-for-enhetstester"),
        clock =
            object : Clock {
                override fun now(): Instant = Instant.parse("2026-10-01T09:00:00Z")
            },
    ),
    lookup,
    metrics,
)

private fun leaderRegistered() =
    FakeNarmesteLederLookup().apply {
        registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, listOf("leder@example.test"), TEST_LEDERRELASJON_ID))
    }

private fun create(
    recipient: ArbeidsgiverRecipient? = null,
    email: NarmesteLederExternalVarslingWire? = null,
) = KalenderavtaleCreate(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient ?: NarmesteLeder(TEST_SYKMELDT, email),
    saktype = Saktype.DIALOGMOTE,
    text = "Dialogmøte med Test",
    link = "https://nav.no/ag",
    startAt = Instant.parse("2026-11-02T09:00:00Z"),
    lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
)

private fun update(
    tilstand: Kalenderavtaletilstand? = null,
    email: NarmesteLederExternalVarslingWire? = null,
    saksstatus: Saksstatus? = null,
) = KalenderavtaleUpdate(
    orgnummer = TEST_ORGNUMMER,
    recipient = NarmesteLeder(TEST_SYKMELDT, email),
    saktype = Saktype.DIALOGMOTE,
    tilstand = tilstand,
    saksstatus = saksstatus,
)

private fun delivery(payload: DispatchContent) =
    ClaimedDelivery(
        claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000903")),
        id = UUID.fromString("00000000-0000-0000-0000-000000000901"),
        inboxEventId = UUID.fromString(EVENT_ID),
        reference = "avtale-1",
        channel = Channel.KALENDERAVTALE,
        payload = payload,
    )
