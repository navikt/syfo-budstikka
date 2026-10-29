package no.nav.budstikka.application.delivery

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.EmailBodyFormat
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstilknytning
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import java.util.UUID
import kotlin.time.Instant
import no.nav.budstikka.contract.AltinnExternalVarsling as AltinnExternalVarslingWire

class ArbeidsgivervarselChannelHandlerTest :
    FunSpec({
        test("publishes arbitrary nonblank Altinn recipient and tag unchanged") {
            val publisher = RecordingPublisher()
            handler(publisher).handle(delivery(create())) shouldBe DeliveryOutcome.Sent

            publisher.requests.single() shouldBe
                ArbeidsgiverNotificationRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    eksternId = "00000000-0000-0000-0000-000000000702",
                    grupperingsid = null,
                    tag = "producer-tag",
                    tekst = "Tekst",
                    lenke = "https://nav.no/lenke",
                    recipient = ArbeidsgiverNotificationRecipient.AltinnRessurs("producer-resource"),
                    meldingstype = no.nav.budstikka.contract.ArbeidsgiverMeldingstype.BESKJED,
                    visibleUntil = null,
                )
        }

        test("ensures the leader relation's case, without the notification's email, before publishing in it") {
            val publisher = RecordingPublisher()
            val casePublisher = FakeArbeidsgiversakPublisher()
            val lookup =
                FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, listOf("leader@example.test"), TEST_LEDERRELASJON_ID))

            handler(publisher, lookup, casePublisher = casePublisher).handle(
                delivery(
                    create(
                        NarmesteLeder(TEST_SYKMELDT, NarmesteLederExternalVarsling("Tittel", "Tekst")),
                        sakstilknytning = DESCRIBED_CASE,
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            casePublisher.caseCreates.single() shouldBe
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = TEST_ORGNUMMER.value,
                    grupperingsid = "sak-1:$TEST_LEDERRELASJON_ID",
                    tag = "producer-tag",
                    tittel = "Oppfølging",
                    lenke = null,
                    initiellStatus = Saksstatus.MOTTATT,
                    hardDeleteAt = null,
                    recipient = ArbeidsgiverNotificationRecipient.NarmesteLeder(LEDER, TEST_SYKMELDT),
                    correlationId = "00000000-0000-0000-0000-000000000702",
                )
            publisher.requests.single().grupperingsid shouldBe "sak-1:$TEST_LEDERRELASJON_ID"
        }

        test("uses the sakId as the case of an Altinn resource") {
            val publisher = RecordingPublisher()
            val casePublisher = FakeArbeidsgiversakPublisher()

            handler(publisher, casePublisher = casePublisher).handle(delivery(create(sakstilknytning = DESCRIBED_CASE))) shouldBe
                DeliveryOutcome.Sent

            casePublisher.caseCreates.single().grupperingsid shouldBe "sak-1"
            publisher.requests.single().grupperingsid shouldBe "sak-1"
        }

        test("does not publish when the case cannot be ensured") {
            val publisher = RecordingPublisher()
            val casePublisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("Konflikt"))

            handler(publisher, casePublisher = casePublisher).handle(delivery(create(sakstilknytning = DESCRIBED_CASE))) shouldBe
                DeliveryOutcome.Failed("Konflikt")
            publisher.requests shouldBe emptyList()
        }

        test("fails an invalid case description before publishing") {
            val publisher = RecordingPublisher()

            handler(publisher).handle(
                delivery(create(sakstilknytning = Sakstilknytning("sak-1", title = " "))),
            ) shouldBe DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL sakstilknytning.title must not be blank")
            publisher.requests shouldBe emptyList()
        }

        test("publishes a link-only case association from the deprecated overloads unchanged") {
            val publisher = RecordingPublisher()
            val casePublisher = FakeArbeidsgiversakPublisher()

            handler(publisher, casePublisher = casePublisher).handle(
                delivery(create(sakstilknytning = Sakstilknytning("sak-1"))),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().grupperingsid shouldBe "sak-1"
            casePublisher.requests shouldBe emptyList()
        }

        test("fails an invalid recipient without a case and does not retry the publish") {
            val publisher =
                RecordingPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMottaker"))

            handler(publisher).handle(delivery(create())) shouldBe DeliveryOutcome.Failed("UgyldigMottaker")

            publisher.requests.size shouldBe 1
        }

        test("forwards visibleUntil to the notification request") {
            val publisher = RecordingPublisher()
            val visibleUntil = Instant.parse("2026-07-01T10:00:00Z")

            handler(publisher).handle(delivery(create(visibleUntil = visibleUntil))) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().visibleUntil shouldBe visibleUntil
        }

        test("fails blank tag before lookup or publishing without leaking recipient data") {
            val publisher = RecordingPublisher()
            val outcome =
                handler(publisher, ThrowingNarmesteLederLookup()).handle(
                    delivery(create(NarmesteLeder(TEST_SYKMELDT), tag = " \t")),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldBe
                "ARBEIDSGIVERVARSEL tag must not be blank"
            publisher.requests shouldBe emptyList()
            outcome.reason shouldNotContain TEST_SYKMELDT.value
        }

        test("fails blank Altinn resource before publishing without leaking tag data") {
            val publisher = RecordingPublisher()
            val outcome =
                handler(publisher).handle(
                    delivery(create(AltinnResource(" \t"), tag = "sensitive-producer-tag")),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldBe
                "ARBEIDSGIVERVARSEL recipient.resource must not be blank"
            publisher.requests shouldBe emptyList()
            outcome.reason shouldNotContain "sensitive-producer-tag"
        }

        test("publishes NarmesteLeder without external notification when email is absent") {
            val publisher = RecordingPublisher()
            handler(
                publisher,
                FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID)),
            ).handle(delivery(create(NarmesteLeder(TEST_SYKMELDT)))) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.NarmesteLeder(LEDER, TEST_SYKMELDT)
        }

        test("publishes NarmesteLeder external notification for all email addresses") {
            val publisher = RecordingPublisher()
            handler(
                publisher,
                FakeNarmesteLederLookup(
                    NarmesteLederRelasjon(LEDER, listOf("first@example.test", "second@example.test"), TEST_LEDERRELASJON_ID),
                ),
            ).handle(
                delivery(
                    create(
                        NarmesteLeder(
                            TEST_SYKMELDT,
                            NarmesteLederExternalVarsling(
                                emailTitle = "Tittel",
                                emailText = "<p><strong>Tekst</strong></p>",
                                emailBodyFormat = EmailBodyFormat.HTML,
                            ),
                        ),
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.NarmesteLeder(
                    LEDER,
                    TEST_SYKMELDT,
                    NarmesteLederExternalVarsling(
                        "Tittel",
                        "<p><strong>Tekst</strong></p>",
                        listOf("first@example.test", "second@example.test"),
                    ),
                )
        }

        test("escapes legacy plain-text email bodies from queued 0.2.0 payloads") {
            val publisher = RecordingPublisher()
            handler(
                publisher,
                FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, listOf("leader@example.test"), TEST_LEDERRELASJON_ID)),
            ).handle(
                delivery(
                    create(
                        NarmesteLeder(
                            TEST_SYKMELDT,
                            NarmesteLederExternalVarsling(
                                emailTitle = "Tittel",
                                emailText = "A & <B>\nNeste",
                            ),
                        ),
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            val externalVarsling =
                (publisher.requests.single().recipient as ArbeidsgiverNotificationRecipient.NarmesteLeder)
                    .externalVarsling
            externalVarsling?.epostHtmlBody shouldBe "A &amp; &lt;B&gt;<br>Neste"
        }

        test("forwards Altinn HTML unchanged and escapes legacy Altinn plain text") {
            val publisher = RecordingPublisher()
            val handler = handler(publisher)

            handler.handle(
                delivery(
                    create(
                        AltinnResource(
                            "producer-resource",
                            AltinnExternalVarslingWire(
                                emailTitle = "Tittel",
                                emailText = "<p><strong>HTML</strong></p>",
                                smsText = "SMS",
                                emailBodyFormat = EmailBodyFormat.HTML,
                            ),
                        ),
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent
            handler.handle(
                delivery(
                    create(
                        AltinnResource(
                            "producer-resource",
                            AltinnExternalVarslingWire(
                                emailTitle = "Tittel",
                                emailText = "A & <B>\nNeste",
                                smsText = "SMS",
                            ),
                        ),
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            val first = publisher.requests[0].recipient as ArbeidsgiverNotificationRecipient.AltinnRessurs
            val second = publisher.requests[1].recipient as ArbeidsgiverNotificationRecipient.AltinnRessurs
            first.externalVarsling?.epostHtmlBody shouldBe "<p><strong>HTML</strong></p>"
            second.externalVarsling?.epostHtmlBody shouldBe "A &amp; &lt;B&gt;<br>Neste"
        }

        test("rejects blank external title and Altinn SMS before lookup or publishing") {
            val publisher = RecordingPublisher()

            val blankTitle =
                handler(publisher, ThrowingNarmesteLederLookup()).handle(
                    delivery(
                        create(
                            NarmesteLeder(
                                TEST_SYKMELDT,
                                NarmesteLederExternalVarsling(" ", "<p>HTML</p>", EmailBodyFormat.HTML),
                            ),
                        ),
                    ),
                ) as DeliveryOutcome.Failed
            val blankSms =
                handler(publisher).handle(
                    delivery(
                        create(
                            AltinnResource(
                                "producer-resource",
                                AltinnExternalVarslingWire("Tittel", "<p>HTML</p>", " ", EmailBodyFormat.HTML),
                            ),
                        ),
                    ),
                ) as DeliveryOutcome.Failed

            blankTitle.reason shouldBe "ARBEIDSGIVERVARSEL external notification emailTitle must not be blank"
            blankSms.reason shouldBe "ARBEIDSGIVERVARSEL external notification smsText must not be blank"
            publisher.requests shouldBe emptyList()
        }

        test("fails terminally with an identifier-free active leader reason") {
            val metrics = RecordingDeliveryMetrics()
            val outcome =
                handler(RecordingPublisher(), FakeNarmesteLederLookup(null), metrics).handle(
                    delivery(
                        create(
                            NarmesteLeder(
                                TEST_SYKMELDT,
                                NarmesteLederExternalVarsling(
                                    emailTitle = "sensitive title",
                                    emailText = "<p>sensitive text secret@example.test</p>",
                                    emailBodyFormat = EmailBodyFormat.HTML,
                                ),
                            ),
                        ),
                    ),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldBe
                "ARBEIDSGIVERVARSEL NarmesteLeder delivery unavailable: missing_active_leader"
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_ACTIVE_LEADER]?.get() shouldBe 1
            outcome.reason shouldNotContain TEST_SYKMELDT.value
            outcome.reason shouldNotContain TEST_ORGNUMMER.value
            outcome.reason shouldNotContain "sensitive title"
            outcome.reason shouldNotContain "sensitive text secret@example.test"
            outcome.reason shouldNotContain "secret@example.test"
        }

        test("fails terminally with an identifier-free missing email reason") {
            val metrics = RecordingDeliveryMetrics()
            val outcome =
                handler(
                    RecordingPublisher(),
                    FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID)),
                    metrics,
                ).handle(
                    delivery(
                        create(
                            NarmesteLeder(
                                TEST_SYKMELDT,
                                NarmesteLederExternalVarsling(
                                    emailTitle = "sensitive title",
                                    emailText = "<p>sensitive text secret@example.test</p>",
                                    emailBodyFormat = EmailBodyFormat.HTML,
                                ),
                            ),
                        ),
                    ),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldBe
                "ARBEIDSGIVERVARSEL NarmesteLeder delivery unavailable: missing_email_address"
            metrics.narmesteLederMissing[NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS]?.get() shouldBe 1
            outcome.reason shouldNotContain TEST_SYKMELDT.value
            outcome.reason shouldNotContain TEST_ORGNUMMER.value
            outcome.reason shouldNotContain "sensitive title"
            outcome.reason shouldNotContain "sensitive text secret@example.test"
            outcome.reason shouldNotContain "secret@example.test"
        }

        test("wraps transient NarmesteLeder lookup failures with channel context") {
            val error =
                shouldThrow<ChannelHandlerFailure> {
                    handler(RecordingPublisher(), ThrowingNarmesteLederLookup()).handle(
                        delivery(create(NarmesteLeder(TEST_SYKMELDT))),
                    )
                }

            error.message shouldContain "ARBEIDSGIVERVARSEL channel failed while resolving nærmeste leder"
            error.cause.shouldBeInstanceOf<IllegalStateException>()
            error.stackTrace.any { it.className.contains("ArbeidsgivervarselChannelHandler") } shouldBe true
        }
    })

private val LEDER = PersonIdentifier("2".repeat(11))
private val DESCRIBED_CASE = Sakstilknytning("sak-1", title = "Oppfølging")

private fun handler(
    publisher: RecordingPublisher,
    lookup: NarmesteLederLookup = FakeNarmesteLederLookup(null),
    metrics: RecordingDeliveryMetrics = RecordingDeliveryMetrics(),
    casePublisher: FakeArbeidsgiversakPublisher = FakeArbeidsgiversakPublisher(),
) = ArbeidsgivervarselChannelHandler(publisher, casePublisher, lookup, metrics)

private fun create(
    recipient: ArbeidsgiverRecipient = AltinnResource("producer-resource"),
    tag: String = "producer-tag",
    visibleUntil: Instant? = null,
    sakstilknytning: Sakstilknytning? = null,
) = ArbeidsgivervarselCreate(
    TEST_ORGNUMMER,
    recipient,
    tag,
    "Tekst",
    "https://nav.no/lenke",
    sakstilknytning = sakstilknytning,
    visibleUntil = visibleUntil,
)

private fun delivery(payload: no.nav.budstikka.contract.DispatchContent) =
    ClaimedDelivery(
        claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000703")),
        id = UUID.fromString("00000000-0000-0000-0000-000000000701"),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000702"),
        reference = "reference",
        channel = Channel.ARBEIDSGIVERVARSEL,
        payload = payload,
    )

private class FakeNarmesteLederLookup(
    private val relation: NarmesteLederRelasjon?,
) : NarmesteLederLookup {
    override suspend fun findActive(
        sykmeldt: no.nav.budstikka.contract.PersonIdentifier,
        orgnummer: no.nav.budstikka.contract.Orgnummer,
    ) = relation
}

private class ThrowingNarmesteLederLookup : NarmesteLederLookup {
    override suspend fun findActive(
        sykmeldt: no.nav.budstikka.contract.PersonIdentifier,
        orgnummer: no.nav.budstikka.contract.Orgnummer,
    ): NarmesteLederRelasjon? = error("narmesteleder-register unavailable")
}

private class RecordingPublisher(
    vararg responses: ArbeidsgiverNotificationResponse,
) : ArbeidsgiverNotificationPublisher {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<ArbeidsgiverNotificationRequest>()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        requests += request
        return responses.removeFirstOrNull() ?: ArbeidsgiverNotificationResponse.Published
    }
}
