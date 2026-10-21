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
import no.nav.budstikka.contract.Sakstilknytning
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID
import kotlin.time.Instant
import no.nav.budstikka.contract.NarmesteLederExternalVarsling as NarmesteLederExternalVarslingWire

private val LEADER = PersonIdentifier("00000000000")
private const val EVENT_ID = "00000000-0000-0000-0000-000000000902"
private val PLAIN_EMAIL = NarmesteLederExternalVarslingWire("Tittel", "A & B")

class KalenderavtaleChannelHandlerTest :
    FunSpec({
        test("creates the event with the dispatch reference as eksternId and escaped leader emails") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher, leaderRegistered()).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Sent

            publisher.calendarCreates.single() shouldBe
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "mote-1",
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
                )
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
                    registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, emptyList()))
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

        test("retries a create while the case does not exist yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"))

            shouldThrow<ChannelHandlerFailure> {
                handler(publisher, leaderRegistered()).handle(delivery(create()))
            }
        }

        test("fails terminally when the leader is not on the case") {
            val publisher =
                FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.InvalidRecipient("UgyldigMottaker"))

            handler(publisher, leaderRegistered()).handle(delivery(create())) shouldBe
                DeliveryOutcome.Failed("UgyldigMottaker")
            publisher.requests.size shouldBe 1
        }

        test("updates the state without a leader lookup when no email is requested") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(update(newState = Kalenderavtaletilstand.AVLYST))) shouldBe
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
                delivery(update(newState = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL)),
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
                handler(publisher).handle(delivery(update(newState = Kalenderavtaletilstand.AVLYST)))
            }
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
) = KalenderavtaleChannelHandler(publisher, lookup, metrics)

private fun leaderRegistered() =
    FakeNarmesteLederLookup().apply {
        registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, listOf("leder@example.test")))
    }

private fun create(
    recipient: ArbeidsgiverRecipient? = null,
    email: NarmesteLederExternalVarslingWire? = null,
) = KalenderavtaleCreate(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient ?: NarmesteLeder(TEST_SYKMELDT, email),
    tag = "Dialogmøte",
    sakstilknytning = Sakstilknytning("mote-1"),
    text = "Dialogmøte med Test",
    link = "https://nav.no/ag",
    startAt = Instant.parse("2026-11-02T09:00:00Z"),
    location = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
)

private fun update(
    newState: Kalenderavtaletilstand? = null,
    email: NarmesteLederExternalVarslingWire? = null,
) = KalenderavtaleUpdate(
    orgnummer = TEST_ORGNUMMER,
    recipient = NarmesteLeder(TEST_SYKMELDT, email),
    tag = "Dialogmøte",
    newState = newState,
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
