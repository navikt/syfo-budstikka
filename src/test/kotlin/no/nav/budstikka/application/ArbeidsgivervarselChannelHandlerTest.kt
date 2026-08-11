package no.nav.budstikka.application

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.port.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.NarmesteLederLookup
import no.nav.budstikka.application.port.NarmesteLederMissingReason
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Tag
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.RecordingDispatchMetrics
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import java.util.UUID

class ArbeidsgivervarselChannelHandlerTest :
    FunSpec({
        test("publishes Altinn recipient with the frozen CREATE external id") {
            val publisher = RecordingPublisher()
            val frozenExternalId = "frozen-create-external-id"
            handler(publisher).handle(delivery(create(), createExternalId = frozenExternalId)) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.AltinnRessurs(AltinnResourceId.DIALOGMOETE)
            publisher.requests.single().eksternId shouldBe frozenExternalId
        }

        test("falls back to the previous CREATE identity when the frozen external id is absent") {
            val publisher = RecordingPublisher()
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000703")

            handler(publisher).handle(delivery(create(), createExternalId = null)) shouldBe DeliveryOutcome.Sent
            handler(publisher).handle(
                delivery(
                    create(),
                    id = deliveryId,
                    inboxEventId = null,
                    createExternalId = null,
                ),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.map(ArbeidsgiverNotificationRequest::eksternId) shouldBe
                listOf("00000000-0000-0000-0000-000000000702", deliveryId.toString())
        }

        listOf(ArbeidsgiverMeldingstype.BESKJED, ArbeidsgiverMeldingstype.OPPGAVE).forEach { meldingstype ->
            test("closes stored $meldingstype with frozen create data without a NarmesteLeder lookup") {
                val publisher = RecordingPublisher()
                val externalId = "00000000-0000-0000-0000-000000000703"

                handler(publisher, ThrowingNarmesteLederLookup()).handle(
                    delivery(
                        create(meldingstype = meldingstype),
                        operation = Operation.INACTIVATE,
                        createExternalId = externalId,
                    ),
                ) shouldBe DeliveryOutcome.Sent

                publisher.requests shouldHaveSize 0
                publisher.closeRequests shouldBe
                    listOf(
                        ArbeidsgiverNotificationCloseRequest(
                            eksternId = externalId,
                            tag = Tag.DIALOGMOETE,
                            meldingstype = meldingstype,
                        ),
                    )
            }
        }

        test("fails to close when the frozen CREATE external id is absent") {
            val publisher = RecordingPublisher()

            val outcome =
                handler(publisher).handle(
                    delivery(
                        create(),
                        operation = Operation.INACTIVATE,
                        createExternalId = null,
                    ),
                )

            (outcome as DeliveryOutcome.Failed).reason shouldBe
                "ARBEIDSGIVERVARSEL inactivate is missing frozen external id"
            publisher.closeRequests shouldHaveSize 0
        }

        test("publishes NarmesteLeder without external notification when email is absent") {
            val publisher = RecordingPublisher()
            handler(
                publisher,
                FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList())),
            ).handle(delivery(create(NarmesteLeder(TEST_SYKMELDT)))) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.NarmesteLeder(LEDER, TEST_SYKMELDT)
        }

        test("publishes NarmesteLeder external notification for all email addresses") {
            val publisher = RecordingPublisher()
            handler(
                publisher,
                FakeNarmesteLederLookup(
                    NarmesteLederRelasjon(LEDER, listOf("first@example.test", "second@example.test")),
                ),
            ).handle(
                delivery(
                    create(
                        NarmesteLeder(
                            TEST_SYKMELDT,
                            NarmesteLederExternalVarsling("Tittel", "Tekst"),
                        ),
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().recipient shouldBe
                ArbeidsgiverNotificationRecipient.NarmesteLeder(
                    LEDER,
                    TEST_SYKMELDT,
                    no.nav.budstikka.application.port.NarmesteLederExternalVarsling(
                        "Tittel",
                        "Tekst",
                        listOf("first@example.test", "second@example.test"),
                    ),
                )
        }

        test("fails terminally with an identifier-free active leader reason") {
            val metrics = RecordingDispatchMetrics()
            val outcome =
                handler(RecordingPublisher(), FakeNarmesteLederLookup(null), metrics).handle(
                    delivery(
                        create(
                            NarmesteLeder(
                                TEST_SYKMELDT,
                                NarmesteLederExternalVarsling(
                                    "sensitive title",
                                    "sensitive text secret@example.test",
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
            val metrics = RecordingDispatchMetrics()
            val outcome =
                handler(
                    RecordingPublisher(),
                    FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList())),
                    metrics,
                ).handle(
                    delivery(
                        create(
                            NarmesteLeder(
                                TEST_SYKMELDT,
                                NarmesteLederExternalVarsling(
                                    "sensitive title",
                                    "sensitive text secret@example.test",
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

private val LEDER = TEST_SYKMELDT_2

private fun handler(
    publisher: RecordingPublisher,
    lookup: NarmesteLederLookup = FakeNarmesteLederLookup(null),
    metrics: RecordingDispatchMetrics = RecordingDispatchMetrics(),
) = ArbeidsgivervarselChannelHandler(publisher, lookup, metrics)

private fun create(
    recipient: ArbeidsgiverRecipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
    meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
) = ArbeidsgivervarselCreate(
    TEST_ORGNUMMER,
    recipient,
    Tag.DIALOGMOETE,
    "Tekst",
    "https://nav.no/lenke",
    meldingstype = meldingstype,
)

private fun delivery(
    payload: no.nav.budstikka.contract.DispatchContent,
    id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000701"),
    inboxEventId: UUID? = UUID.fromString("00000000-0000-0000-0000-000000000702"),
    operation: Operation = Operation.CREATE,
    createExternalId: String? = "00000000-0000-0000-0000-000000000702",
) = ClaimedDelivery(
    id = id,
    inboxEventId = inboxEventId,
    reference = "reference",
    channel = Channel.ARBEIDSGIVERVARSEL,
    payload = payload,
    operation = operation,
    createExternalId = createExternalId,
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

private class RecordingPublisher : ArbeidsgiverNotificationPublisher {
    val requests = mutableListOf<ArbeidsgiverNotificationRequest>()
    val closeRequests = mutableListOf<ArbeidsgiverNotificationCloseRequest>()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        requests += request
        return ArbeidsgiverNotificationResponse.Published
    }

    override suspend fun close(request: ArbeidsgiverNotificationCloseRequest): ArbeidsgiverNotificationResponse {
        closeRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }
}
