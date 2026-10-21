package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgiversakCreate
import no.nav.budstikka.contract.ArbeidsgiversakStatus
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstilknytning
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")
private const val EVENT_ID = "00000000-0000-0000-0000-000000000802"

class ArbeidsgiversakChannelHandlerTest :
    FunSpec({
        test("creates the case for the active leader resolved at send time") {
            val publisher = FakeArbeidsgiversakPublisher()
            val hardDeleteAt = Instant.parse("2026-12-01T00:00:00Z")

            handler(publisher, leaderRegistered()).handle(delivery(create(hardDeleteAt = hardDeleteAt))) shouldBe
                DeliveryOutcome.Sent

            publisher.caseCreates.single() shouldBe
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "mote-1",
                    tag = "Dialogmøte",
                    tittel = "Dialogmøte med Test",
                    lenke = null,
                    initiellStatus = Saksstatus.MOTTATT,
                    hardDeleteAt = hardDeleteAt,
                    recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT),
                    correlationId = EVENT_ID,
                )
        }

        test("creates the case for an Altinn resource without a leader lookup") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(create(recipient = AltinnResource("nav_syfo_dialogmote")))) shouldBe
                DeliveryOutcome.Sent

            publisher.caseCreates.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.AltinnRessurs("nav_syfo_dialogmote")
        }

        test("fails terminally and meters a missing leader without publishing") {
            val publisher = FakeArbeidsgiversakPublisher()
            val metrics = RecordingDeliveryMetrics()

            val outcome = handler(publisher, metrics = metrics).handle(delivery(create()))

            outcome shouldBe DeliveryOutcome.Failed("ARBEIDSGIVERSAK NarmesteLeder delivery unavailable: missing_active_leader")
            publisher.requests shouldBe emptyList()
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
        }

        test("fails blank fields before any lookup without leaking their values") {
            val publisher = FakeArbeidsgiversakPublisher()

            val outcome =
                handler(publisher).handle(
                    delivery(create(title = " ", sakId = "sensitive-case-id")),
                ) as DeliveryOutcome.Failed

            outcome.reason shouldBe "ARBEIDSGIVERSAK title must not be blank"
            outcome.reason shouldNotContain "sensitive-case-id"
            publisher.requests shouldBe emptyList()
        }

        test("sets the case status with the dispatch as idempotency key") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(status())) shouldBe DeliveryOutcome.Sent

            publisher.caseStatuses.single() shouldBe
                ArbeidsgiversakStatusRequest(
                    grupperingsid = "mote-1",
                    tag = "Dialogmøte",
                    status = Saksstatus.FERDIG,
                    statustekst = "Avlyst",
                    hardDeleteAt = null,
                    idempotencyKey = EVENT_ID,
                )
        }

        test("retries a status for a case that does not exist yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"))

            shouldThrow<ChannelHandlerFailure> { handler(publisher).handle(delivery(status())) }
        }

        test("passes a downstream rejection through as a terminal failure") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("Konflikt"))

            handler(publisher).handle(delivery(status())) shouldBe DeliveryOutcome.Failed("Konflikt")
        }

        test("rejects a payload from another channel") {
            val outcome =
                handler(FakeArbeidsgiversakPublisher()).handle(
                    delivery(
                        no.nav.budstikka.contract
                            .BrevCreate(TEST_SYKMELDT, "jp-1"),
                    ),
                )

            outcome shouldBe DeliveryOutcome.Failed("Payload does not match ARBEIDSGIVERSAK channel: BrevCreate")
        }
    })

private fun handler(
    publisher: FakeArbeidsgiversakPublisher,
    lookup: NarmesteLederLookup = FakeNarmesteLederLookup(),
    metrics: RecordingDeliveryMetrics = RecordingDeliveryMetrics(),
) = ArbeidsgiversakChannelHandler(publisher, lookup, metrics)

private fun leaderRegistered() =
    FakeNarmesteLederLookup().apply {
        registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, listOf("leder@example.test")))
    }

private fun create(
    recipient: ArbeidsgiverRecipient = NarmesteLeder(TEST_SYKMELDT),
    title: String = "Dialogmøte med Test",
    sakId: String = "mote-1",
    hardDeleteAt: Instant? = null,
) = ArbeidsgiversakCreate(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient,
    tag = "Dialogmøte",
    sakstilknytning = Sakstilknytning(sakId),
    title = title,
    hardDeleteAt = hardDeleteAt,
)

private fun status() =
    ArbeidsgiversakStatus(
        orgnummer = TEST_ORGNUMMER,
        tag = "Dialogmøte",
        sakstilknytning = Sakstilknytning("mote-1"),
        status = Saksstatus.FERDIG,
        statusText = "Avlyst",
    )

private fun delivery(payload: DispatchContent) =
    ClaimedDelivery(
        claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000803")),
        id = UUID.fromString("00000000-0000-0000-0000-000000000801"),
        inboxEventId = UUID.fromString(EVENT_ID),
        reference = "reference",
        channel = Channel.ARBEIDSGIVERSAK,
        payload = payload,
    )
