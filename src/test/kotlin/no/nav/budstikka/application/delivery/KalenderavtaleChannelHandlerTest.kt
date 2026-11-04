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
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
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
private val SNAPSHOT_LEADER_RELATION_ID = UUID.fromString("00000000-0000-4000-8000-00000000aa02")

class KalenderavtaleChannelHandlerTest :
    FunSpec({
        test("creates the event from the leader and name saved in the delivery snapshot") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )
            val snapshotLeader =
                NarmesteLederRelasjon(
                    LEADER,
                    listOf("snapshot@example.test"),
                    SNAPSHOT_LEADER_RELATION_ID,
                )
            val snapshot =
                DeliverySnapshot.Resolved(
                    leader = snapshotLeader,
                    name = PersonName("Kari", null, "Nordmann"),
                )

            handler(publisher).handle(delivery(create(), snapshot)) shouldBe DeliveryOutcome.Sent

            publisher.sakCreates.single().title shouldBe "Dialogmøte med Kari Nordmann"
            publisher.kalenderavtaleCreates
                .single()
                .recipient
                .narmesteLederFnr shouldBe snapshotLeader.narmesteLederFnr
            publisher.kalenderavtaleCreates
                .single()
                .grupperingsid shouldBe "DIALOGMOTE:$SNAPSHOT_LEADER_RELATION_ID"
        }

        test("fails a legacy calendar snapshot without publishing or resolving it") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(create(), DeliverySnapshot.Legacy)) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE legacy delivery snapshot is unsupported")

            publisher.requests shouldBe emptyList()
        }

        test("rejects an Altinn recipient on a plain update before publishing") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST).copy(recipient = AltinnResource("nav_syfo_dialogmote"))),
            ) shouldBe DeliveryOutcome.Failed("KALENDERAVTALE supports only a NarmesteLeder recipient")

            publisher.requests shouldBe emptyList()
        }

        test("creates the event in the leader relation's sak with the dispatch reference as eksternId") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )

            handler(publisher).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Sent

            publisher.kalenderavtaleCreates.single() shouldBe
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    eksternId = "avtale-1",
                    text = "Dialogmøte med Test",
                    link = "https://nav.no/ag",
                    startAt = Instant.parse("2026-11-02T09:00:00Z"),
                    endAt = null,
                    lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
                    digital = false,
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
            publisher.sakCreates.single().title shouldBe "Dialogmøte"
        }

        test("extends the leader relation's sak to four months past the event before creating it") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(create(email = PLAIN_EMAIL))) shouldBe
                DeliveryOutcome.Sent

            publisher.requests.first() shouldBe
                ArbeidsgiversakExtendRequest(
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    keepUntil = Instant.parse("2027-03-02T09:00:00Z"),
                    correlationId = EVENT_ID,
                )
        }

        test("sets the requested saksstatus after creating the event") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
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

        test("an update changes only the event, not its sak") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(update(tilstand = Kalenderavtaletilstand.AVLYST))) shouldBe DeliveryOutcome.Sent

            publisher.requests.single() shouldBe
                KalenderavtaleUpdateRequest(
                    tag = "Dialogmøte",
                    eksternId = "avtale-1",
                    tilstand = Kalenderavtaletilstand.AVLYST,
                    text = null,
                    externalVarsling = null,
                    idempotencyKey = EVENT_ID,
                )
        }

        test("an update sets its status on the saved leader's sak before the update") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL, saksstatus = Saksstatus.FERDIG)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.map { it::class } shouldBe
                listOf(ArbeidsgiversakExtendRequest::class, ArbeidsgiversakStatusRequest::class, KalenderavtaleUpdateRequest::class)
            publisher.saksstatuser.single() shouldBe
                ArbeidsgiversakStatusRequest("DIALOGMOTE:$TEST_LEDERRELASJON_ID", "Dialogmøte", Saksstatus.FERDIG, EVENT_ID)
            publisher.kalenderavtaleUpdates
                .single()
                .externalVarsling
                ?.epostadresser shouldBe listOf("leder@example.test")
        }

        test("a failed status is retried before the update, so the update and its email are sent once") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )
            val savedSnapshot =
                DeliverySnapshot.Resolved(
                    leader =
                        NarmesteLederRelasjon(
                            LEADER,
                            listOf("snapshot@example.test"),
                            SNAPSHOT_LEADER_RELATION_ID,
                        ),
                    name = PersonName("Kari", null, "Nordmann"),
                )
            val delivery =
                delivery(
                    update(tilstand = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL, saksstatus = Saksstatus.FERDIG),
                    savedSnapshot,
                )

            shouldThrow<ChannelHandlerFailure> {
                handler(publisher).handle(delivery)
            }
            publisher.kalenderavtaleUpdates shouldBe emptyList()

            handler(publisher).handle(delivery) shouldBe DeliveryOutcome.Sent
            publisher.requests.map { it::class } shouldBe
                listOf(
                    ArbeidsgiversakExtendRequest::class,
                    ArbeidsgiversakStatusRequest::class,
                    ArbeidsgiversakExtendRequest::class,
                    ArbeidsgiversakStatusRequest::class,
                    KalenderavtaleUpdateRequest::class,
                )
            publisher.kalenderavtaleUpdates.size shouldBe 1
            publisher.kalenderavtaleUpdates
                .single()
                .externalVarsling
                ?.epostadresser shouldBe listOf("snapshot@example.test")
            publisher.saksstatuser
                .map { it.grupperingsid }
                .distinct() shouldBe listOf("DIALOGMOTE:$SNAPSHOT_LEADER_RELATION_ID")
        }

        test("fails when an update snapshot has no leader") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, saksstatus = Saksstatus.FERDIG), DeliverySnapshot.Resolved()),
            ) shouldBe DeliveryOutcome.Failed("KALENDERAVTALE resolved delivery snapshot is missing a leader")
            publisher.requests shouldBe emptyList()
        }

        test("forwards explicit HTML email unchanged") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
                delivery(create(email = NarmesteLederExternalVarslingWire("Tittel", "<p>Hei</p>", EmailBodyFormat.HTML))),
            )

            publisher.kalenderavtaleCreates
                .single()
                .recipient.externalVarsling
                ?.epostHtmlBody shouldBe "<p>Hei</p>"
        }

        test("fails terminally when an email is requested but the leader has no address") {
            val publisher = FakeArbeidsgiversakPublisher()
            val noEmailSnapshot =
                DeliverySnapshot.Resolved(
                    leader = NarmesteLederRelasjon(LEADER, emptyList(), TEST_LEDERRELASJON_ID),
                )

            handler(publisher).handle(delivery(create(email = PLAIN_EMAIL), noEmailSnapshot)) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE NarmesteLeder delivery unavailable: missing_email_address")
            publisher.requests shouldBe emptyList()
        }

        test("fails terminally when the resolved snapshot has no leader") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(create(), DeliverySnapshot.Resolved())) shouldBe
                DeliveryOutcome.Failed("KALENDERAVTALE resolved delivery snapshot is missing a leader")
            publisher.requests shouldBe emptyList()
        }

        test("does not support an Altinn recipient") {
            handler(FakeArbeidsgiversakPublisher()).handle(
                delivery(create(recipient = AltinnResource("nav_syfo_dialogmote"))),
            ) shouldBe DeliveryOutcome.Failed("KALENDERAVTALE supports only a NarmesteLeder recipient")
        }

        test("retries a create while the ensured sak is not visible yet") {
            val publisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )

            shouldThrow<ChannelHandlerFailure> {
                handler(publisher).handle(delivery(create()))
            }
        }

        test("stops before the event when the sak cannot be ensured") {
            val publisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))

            handler(publisher).handle(delivery(create())) shouldBe
                DeliveryOutcome.Failed("UgyldigMerkelapp")
            publisher.kalenderavtaleCreates shouldBe emptyList()
        }

        test("updates state using the saved snapshot when no email is requested") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(update(tilstand = Kalenderavtaletilstand.AVLYST))) shouldBe
                DeliveryOutcome.Sent

            publisher.kalenderavtaleUpdates.single() shouldBe
                KalenderavtaleUpdateRequest(
                    tag = "Dialogmøte",
                    eksternId = "avtale-1",
                    tilstand = Kalenderavtaletilstand.AVLYST,
                    text = null,
                    externalVarsling = null,
                    idempotencyKey = EVENT_ID,
                )
        }

        test("updates only the text using the saved leader snapshot") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(delivery(update(text = "Oppdatert tekst"))) shouldBe DeliveryOutcome.Sent

            publisher.kalenderavtaleUpdates.single().text shouldBe "Oppdatert tekst"
            publisher.sakCreates shouldBe emptyList()
            publisher.sakExtensions shouldBe emptyList()
        }

        test("uses the saved leader's addresses when an update requests an email") {
            val publisher = FakeArbeidsgiversakPublisher()

            handler(publisher).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.kalenderavtaleUpdates
                .single()
                .externalVarsling
                ?.epostadresser shouldBe listOf("leder@example.test")
        }

        test("fails before updating when a requested email has no saved address") {
            val publisher = FakeArbeidsgiversakPublisher()
            val snapshotWithoutEmail =
                DeliverySnapshot.Resolved(
                    leader = NarmesteLederRelasjon(LEADER, emptyList(), TEST_LEDERRELASJON_ID),
                )

            handler(publisher).handle(
                delivery(update(tilstand = Kalenderavtaletilstand.AVLYST, email = PLAIN_EMAIL), snapshotWithoutEmail),
            ) shouldBe DeliveryOutcome.Failed("KALENDERAVTALE NarmesteLeder delivery unavailable: missing_email_address")

            publisher.requests shouldBe emptyList()
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

            handler(publisher).handle(delivery(create().copy(text = "x".repeat(301)))) shouldBe
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

private fun handler(publisher: FakeArbeidsgiversakPublisher) =
    KalenderavtaleChannelHandler(
        publisher,
        Arbeidsgiversaker(
            publisher,
            ArbeidsgiversakConfig("https://dinesykmeldte.test"),
            clock =
                object : Clock {
                    override fun now(): Instant = Instant.parse("2026-10-01T09:00:00Z")
                },
        ),
    )

private fun create(
    recipient: ArbeidsgiverRecipient? = null,
    email: NarmesteLederExternalVarslingWire? = null,
) = KalenderavtaleCreate(
    orgnummer = TEST_ORGNUMMER,
    recipient = recipient ?: NarmesteLeder(TEST_SYKMELDT, email),
    sakstype = Sakstype.DIALOGMOTE,
    text = "Dialogmøte med Test",
    link = "https://nav.no/ag",
    startAt = Instant.parse("2026-11-02T09:00:00Z"),
    lokasjon = Kalenderavtalelokasjon("Gate 1", "0001", "Oslo"),
)

private fun update(
    tilstand: Kalenderavtaletilstand? = null,
    email: NarmesteLederExternalVarslingWire? = null,
    saksstatus: Saksstatus? = null,
    text: String? = null,
) = KalenderavtaleUpdate(
    orgnummer = TEST_ORGNUMMER,
    recipient = NarmesteLeder(TEST_SYKMELDT, email),
    sakstype = Sakstype.DIALOGMOTE,
    text = text,
    tilstand = tilstand,
    saksstatus = saksstatus,
)

private fun delivery(
    payload: DispatchContent,
    snapshot: DeliverySnapshot =
        DeliverySnapshot.Resolved(
            leader = NarmesteLederRelasjon(LEADER, listOf("leder@example.test"), TEST_LEDERRELASJON_ID),
        ),
) = ClaimedDelivery(
    claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000903")),
    id = UUID.fromString("00000000-0000-0000-0000-000000000901"),
    inboxEventId = UUID.fromString(EVENT_ID),
    reference = "avtale-1",
    channel = Channel.KALENDERAVTALE,
    payload = payload,
    snapshot = snapshot,
)
