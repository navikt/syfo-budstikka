package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
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
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID
import kotlin.time.Instant

private val LEADER = PersonIdentifier("00000000000")
private const val EVENT_ID = "00000000-0000-0000-0000-000000000802"

class ArbeidsgiversakChannelHandlerTest :
    FunSpec({
        test("ensures the leader relation's case before setting its status") {
            val publisher = FakeArbeidsgiversakPublisher()
            val hardDeleteAt = Instant.parse("2026-12-01T00:00:00Z")

            handler(publisher, leaderRegistered()).handle(delivery(status(hardDeleteAt = hardDeleteAt))) shouldBe
                DeliveryOutcome.Sent

            publisher.requests shouldBe
                listOf(
                    ArbeidsgiversakCreateRequest(
                        virksomhetsnummer = TEST_ORGNUMMER.value,
                        grupperingsid = "mote-1:$TEST_LEDERRELASJON_ID",
                        tag = "Dialogmøte",
                        tittel = "Dialogmøte med Test",
                        lenke = null,
                        initiellStatus = Saksstatus.MOTTATT,
                        hardDeleteAt = hardDeleteAt,
                        recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(LEADER, TEST_SYKMELDT),
                        correlationId = EVENT_ID,
                    ),
                    ArbeidsgiversakStatusRequest(
                        grupperingsid = "mote-1:$TEST_LEDERRELASJON_ID",
                        tag = "Dialogmøte",
                        status = Saksstatus.FERDIG,
                        statustekst = "Avlyst",
                        hardDeleteAt = hardDeleteAt,
                        idempotencyKey = EVENT_ID,
                    ),
                )
        }

        test("gives a new leader a case of their own for the same sakId") {
            val publisher = FakeArbeidsgiversakPublisher()
            val newRelation = UUID.fromString("00000000-0000-4000-8000-00000000aa02")

            handler(publisher, leaderRegistered()).handle(delivery(status())) shouldBe DeliveryOutcome.Sent
            handler(publisher, leaderRegistered(newRelation)).handle(delivery(status())) shouldBe DeliveryOutcome.Sent

            val (previous, current) = publisher.caseStatuses.map { it.grupperingsid }
            previous shouldNotBe current
            current shouldBe "mote-1:$newRelation"
        }

        test("uses the sakId as the case of an Altinn resource without a leader lookup") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(status(recipient = AltinnResource("nav_syfo_dialogmote")))) shouldBe
                DeliveryOutcome.Sent

            publisher.caseCreates.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.AltinnRessurs("nav_syfo_dialogmote")
            publisher.caseStatuses.single().grupperingsid shouldBe "mote-1"
        }

        test("fails terminally and meters a missing leader without publishing") {
            val publisher = FakeArbeidsgiversakPublisher()
            val metrics = RecordingDeliveryMetrics()

            val outcome = handler(publisher, metrics = metrics).handle(delivery(status()))

            outcome shouldBe DeliveryOutcome.Failed("ARBEIDSGIVERSAK NarmesteLeder delivery unavailable: missing_active_leader")
            publisher.requests shouldBe emptyList()
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
        }

        test("fails an invalid case description before any lookup without leaking its values") {
            val publisher = FakeArbeidsgiversakPublisher()

            val blankTitle =
                handler(publisher).handle(
                    delivery(status(sak = Sakstilknytning("sensitive-case-id", title = " "))),
                ) as DeliveryOutcome.Failed

            blankTitle.reason shouldBe "ARBEIDSGIVERSAK sakstilknytning.title must not be blank"
            blankTitle.reason shouldNotContain "sensitive-case-id"
            handler(publisher).handle(delivery(status(sak = Sakstilknytning("mote-1", title = "x".repeat(141))))) shouldBe
                DeliveryOutcome.Failed("ARBEIDSGIVERSAK sakstilknytning.title must be at most 140 characters")
            handler(publisher).handle(delivery(status(sak = Sakstilknytning(" ", title = "Dialogmøte")))) shouldBe
                DeliveryOutcome.Failed("ARBEIDSGIVERSAK sakstilknytning.sakId must not be blank")
            publisher.requests shouldBe emptyList()
        }

        test("stops before the status when the case cannot be ensured") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("Konflikt"))

            handler(publisher, leaderRegistered()).handle(delivery(status())) shouldBe DeliveryOutcome.Failed("Konflikt")
            publisher.caseStatuses shouldBe emptyList()
        }

        test("retries a status for a case that is not visible yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )

            shouldThrow<ChannelHandlerFailure> { handler(publisher, leaderRegistered()).handle(delivery(status())) }
        }

        test("passes a status rejection through as a terminal failure") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.Rejected("Konflikt"),
                )

            handler(publisher, leaderRegistered()).handle(delivery(status())) shouldBe DeliveryOutcome.Failed("Konflikt")
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

private fun leaderRegistered(relasjonId: UUID = TEST_LEDERRELASJON_ID) =
    FakeNarmesteLederLookup().apply {
        registerActive(TEST_SYKMELDT, TEST_ORGNUMMER, NarmesteLederRelasjon(LEADER, listOf("leder@example.test"), relasjonId))
    }

private fun status(
    recipient: ArbeidsgiverRecipient = NarmesteLeder(TEST_SYKMELDT),
    hardDeleteAt: Instant? = null,
    sak: Sakstilknytning = Sakstilknytning("mote-1", title = "Dialogmøte med Test", hardDeleteAt = hardDeleteAt),
) = ArbeidsgiversakStatus(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient,
    tag = "Dialogmøte",
    sakstilknytning = sak,
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
