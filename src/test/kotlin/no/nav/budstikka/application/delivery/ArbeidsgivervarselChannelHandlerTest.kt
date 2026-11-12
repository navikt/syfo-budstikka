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
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.FakePersonNameLookup
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

        test("a sakstype puts the notification in the leader relation's sak with its merkelapp") {
            val publisher = RecordingPublisher()
            val sakPublisher = FakeArbeidsgiversakPublisher()
            val lookup =
                FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, listOf("leader@example.test"), TEST_LEDERRELASJON_ID))

            handler(publisher, lookup, sakPublisher = sakPublisher).handle(
                delivery(
                    create(
                        NarmesteLeder(TEST_SYKMELDT, NarmesteLederExternalVarsling("Tittel", "Tekst")),
                        tag = null,
                        sakstype = Sakstype.DIALOGMOTE,
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            sakPublisher.sakExtensions.single().grupperingsid shouldBe "DIALOGMOTE:$TEST_LEDERRELASJON_ID"
            publisher.requests.single().apply {
                grupperingsid shouldBe "DIALOGMOTE:$TEST_LEDERRELASJON_ID"
                tag shouldBe "Dialogmøte"
            }
        }

        test("a sakstype for an Altinn resource needs the Sykmeldt the sak is about") {
            val publisher = RecordingPublisher()
            val sakPublisher = FakeArbeidsgiversakPublisher()

            handler(publisher, sakPublisher = sakPublisher).handle(
                delivery(create(AltinnResource("nav_syfo_dialogmote"), tag = null, sakstype = Sakstype.DIALOGMOTE)),
            ) shouldBe DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL recipient.sykmeldt and recipient.sakId must be set with sakstype")
            handler(publisher, sakPublisher = sakPublisher).handle(
                delivery(
                    create(
                        AltinnResource("nav_syfo_dialogmote", sykmeldt = TEST_SYKMELDT, sakId = "dialog-1"),
                        tag = null,
                        sakstype = Sakstype.OPPFOLGINGSPLAN,
                    ),
                ),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().apply {
                grupperingsid shouldBe "OPPFOLGINGSPLAN:nav_syfo_dialogmote:${TEST_ORGNUMMER.value}:dialog-1"
                tag shouldBe "Oppfølging"
            }
        }

        listOf("dialog:2", "x".repeat(65)).forEachIndexed { index, sakId ->
            test("an Altinn sak key that could break the key is rejected (#$index)") {
                val sakPublisher = FakeArbeidsgiversakPublisher()

                handler(RecordingPublisher(), sakPublisher = sakPublisher).handle(
                    delivery(
                        create(
                            AltinnResource("nav_syfo_dialogmote", sykmeldt = TEST_SYKMELDT, sakId = sakId),
                            tag = null,
                            sakstype = Sakstype.DIALOGMOTE,
                        ),
                    ),
                ) shouldBe
                    DeliveryOutcome.Failed(
                        "ARBEIDSGIVERVARSEL recipient.sakId must be 1-64 letters, digits, '-' or '_'",
                    )
                sakPublisher.requests shouldBe emptyList()
            }
        }

        test("does not publish when the sak cannot be ensured") {
            val publisher = RecordingPublisher()
            val sakPublisher = FakeArbeidsgiversakPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMerkelapp"))
            val lookup = FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID))

            handler(publisher, lookup, sakPublisher = sakPublisher).handle(
                delivery(create(NarmesteLeder(TEST_SYKMELDT), tag = null, sakstype = Sakstype.DIALOGMOTE)),
            ) shouldBe DeliveryOutcome.Failed("UgyldigMerkelapp")
            publisher.requests shouldBe emptyList()
        }

        test("sets the requested saksstatus after the notification") {
            val publisher = RecordingPublisher()
            val sakPublisher = FakeArbeidsgiversakPublisher()
            val lookup = FakeNarmesteLederLookup(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID))

            handler(publisher, lookup, sakPublisher = sakPublisher).handle(
                delivery(create(NarmesteLeder(TEST_SYKMELDT), tag = null, sakstype = Sakstype.DIALOGMOTE, saksstatus = Saksstatus.FERDIG)),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.size shouldBe 1
            sakPublisher.saksstatuser.single().apply {
                grupperingsid shouldBe "DIALOGMOTE:$TEST_LEDERRELASJON_ID"
                status shouldBe Saksstatus.FERDIG
            }
        }

        test("retries a transient saksstatus failure with the saved leader and the same notification and status key") {
            val publisher = RecordingPublisher()
            val sakPublisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.Published,
                )
            val handler = handler(publisher, ThrowingNarmesteLederLookup(), sakPublisher = sakPublisher)
            val delivery =
                delivery(
                    create(
                        NarmesteLeder(TEST_SYKMELDT, NarmesteLederExternalVarsling("Tittel", "Tekst")),
                        tag = null,
                        sakstype = Sakstype.DIALOGMOTE,
                        saksstatus = Saksstatus.FERDIG,
                    ),
                ).copy(
                    snapshot =
                        DeliverySnapshot.Resolved(
                            NarmesteLederRelasjon(LEDER, listOf("leader@example.test"), TEST_LEDERRELASJON_ID),
                        ),
                )

            shouldThrow<ChannelHandlerFailure> { handler.handle(delivery) }
            publisher.requests.size shouldBe 1
            sakPublisher.saksstatuser.size shouldBe 1

            handler.handle(delivery) shouldBe DeliveryOutcome.Sent
            publisher.requests.size shouldBe 2
            publisher.requests[1] shouldBe publisher.requests[0]
            val status =
                ArbeidsgiversakStatusRequest(
                    grupperingsid = "DIALOGMOTE:$TEST_LEDERRELASJON_ID",
                    tag = "Dialogmøte",
                    status = Saksstatus.FERDIG,
                    idempotencyKey = "00000000-0000-0000-0000-000000000702",
                )
            sakPublisher.saksstatuser shouldBe listOf(status, status)
        }

        test("does not set saksstatus for an unverified duplicate after the leader email changes") {
            var previous: ArbeidsgiverNotificationRequest? = null
            val publisher =
                object : ArbeidsgiverNotificationPublisher {
                    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
                        val existing = previous
                        if (existing == null) {
                            previous = request
                            return ArbeidsgiverNotificationResponse.Published
                        }
                        return if (existing == request) {
                            ArbeidsgiverNotificationResponse.Published
                        } else {
                            ArbeidsgiverNotificationResponse.Rejected("DuplikatEksternIdOgMerkelapp")
                        }
                    }
                }
            val sakPublisher =
                FakeArbeidsgiversakPublisher(
                    ArbeidsgiverNotificationResponse.Published,
                    ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke"),
                )
            var relation = NarmesteLederRelasjon(LEDER, listOf("first@example.test"), TEST_LEDERRELASJON_ID)
            val lookup =
                object : NarmesteLederLookup {
                    override suspend fun findActive(
                        sykmeldt: PersonIdentifier,
                        orgnummer: no.nav.budstikka.contract.Orgnummer,
                    ) = relation
                }
            val handler =
                ArbeidsgivervarselChannelHandler(
                    publisher,
                    Arbeidsgiversaker(sakPublisher, ArbeidsgiversakConfig("https://dinesykmeldte.test")),
                    LegacyDeliverySnapshotResolver(lookup, FakePersonNameLookup(), RecordingDeliveryMetrics()),
                )
            val delivery =
                delivery(
                    create(
                        NarmesteLeder(TEST_SYKMELDT, NarmesteLederExternalVarsling("Tittel", "Tekst")),
                        tag = null,
                        sakstype = Sakstype.DIALOGMOTE,
                        saksstatus = Saksstatus.FERDIG,
                    ),
                )

            shouldThrow<ChannelHandlerFailure> { handler.handle(delivery) }
            relation = relation.copy(epostadresser = listOf("second@example.test"))
            handler.handle(delivery) shouldBe DeliveryOutcome.Failed("DuplikatEksternIdOgMerkelapp")
            sakPublisher.saksstatuser.size shouldBe 1
        }

        test("a saksstatus needs a sakstype") {
            handler(RecordingPublisher()).handle(delivery(create(saksstatus = Saksstatus.FERDIG))) shouldBe
                DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL saksstatus needs a sakstype")
        }

        test("a tag cannot be combined with a sakstype") {
            val publisher = RecordingPublisher()

            handler(publisher).handle(delivery(create(sakstype = Sakstype.DIALOGMOTE))) shouldBe
                DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL tag must not be set with sakstype")
            publisher.requests shouldBe emptyList()
        }

        test("publishes a link-only sak association from the deprecated overloads unchanged") {
            val publisher = RecordingPublisher()
            val sakPublisher = FakeArbeidsgiversakPublisher()

            handler(publisher, sakPublisher = sakPublisher).handle(
                delivery(create(sakstilknytning = Sakstilknytning("sak-1"))),
            ) shouldBe DeliveryOutcome.Sent

            publisher.requests.single().grupperingsid shouldBe "sak-1"
            sakPublisher.requests shouldBe emptyList()
        }

        listOf(
            Triple("without a sak", create(), null),
            Triple("with a producer-owned sak", create(sakstilknytning = Sakstilknytning("sak-1")), "sak-1"),
            Triple(
                "with a Budstikka-owned sak",
                create(
                    AltinnResource("nav_syfo_dialogmote", sykmeldt = TEST_SYKMELDT, sakId = "dialog-1"),
                    tag = null,
                    sakstype = Sakstype.DIALOGMOTE,
                    saksstatus = Saksstatus.FERDIG,
                ),
                "DIALOGMOTE:nav_syfo_dialogmote:${TEST_ORGNUMMER.value}:dialog-1",
            ),
        ).forEach { (scenario, payload, expectedGrupperingsid) ->
            test("fails an invalid recipient $scenario without retrying the publish") {
                val publisher = RecordingPublisher(ArbeidsgiverNotificationResponse.Rejected("UgyldigMottaker"))
                val sakPublisher = FakeArbeidsgiversakPublisher()

                handler(publisher, sakPublisher = sakPublisher).handle(delivery(payload)) shouldBe
                    DeliveryOutcome.Failed("UgyldigMottaker")

                publisher.requests.single().grupperingsid shouldBe expectedGrupperingsid
                sakPublisher.saksstatuser shouldBe emptyList()
            }
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

        test("defaults a leader notification link to the same Dine sykmeldte destination without a sakstype") {
            val publisher = RecordingPublisher()
            handler(publisher, ThrowingNarmesteLederLookup())
                .handle(
                    delivery(create(NarmesteLeder(TEST_SYKMELDT), link = null)).copy(
                        snapshot = DeliverySnapshot.Resolved(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID)),
                    ),
                ).shouldBe(DeliveryOutcome.Sent)
            publisher.requests.single().lenke shouldBe "https://dinesykmeldte.test/$TEST_LEDERRELASJON_ID"
        }

        listOf(
            "https://www.nav.no/oppfolgingsplan/NARMESTE_LEDER_ID/detaljer/NARMESTE_LEDER_ID" to
                "https://www.nav.no/oppfolgingsplan/$TEST_LEDERRELASJON_ID/detaljer/$TEST_LEDERRELASJON_ID",
            "https://www.nav.no/arbeidsgiver/sykmeldte/already-embedded-id" to
                "https://www.nav.no/arbeidsgiver/sykmeldte/already-embedded-id",
            "https://legacy.example.test/old-path" to "https://legacy.example.test/old-path",
        ).forEachIndexed { index, (link, expected) ->
            test("stored custom link substitutes only the placeholder, without revalidating the host ($index)") {
                val publisher = RecordingPublisher()
                handler(publisher, ThrowingNarmesteLederLookup()).handle(
                    delivery(create(NarmesteLeder(TEST_SYKMELDT), link = link)).copy(
                        snapshot = DeliverySnapshot.Resolved(NarmesteLederRelasjon(LEDER, emptyList(), TEST_LEDERRELASJON_ID)),
                    ),
                ) shouldBe DeliveryOutcome.Sent
                publisher.requests.single().lenke shouldBe expected
            }
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
                "MISSING_ACTIVE_LEADER"
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
                "MISSING_EMAIL_ADDRESS"
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

            error.message shouldContain "ARBEIDSGIVERVARSEL channel failed while resolving legacy delivery snapshot"
            error.cause.shouldBeInstanceOf<IllegalStateException>()
            error.stackTrace.any { it.className.contains("ArbeidsgivervarselChannelHandler") } shouldBe true
        }
    })

private val LEDER = PersonIdentifier("2".repeat(11))

private fun handler(
    publisher: RecordingPublisher,
    lookup: NarmesteLederLookup = FakeNarmesteLederLookup(null),
    metrics: RecordingDeliveryMetrics = RecordingDeliveryMetrics(),
    sakPublisher: FakeArbeidsgiversakPublisher = FakeArbeidsgiversakPublisher(),
) = ArbeidsgivervarselChannelHandler(
    publisher,
    Arbeidsgiversaker(
        sakPublisher,
        ArbeidsgiversakConfig("https://dinesykmeldte.test"),
    ),
    LegacyDeliverySnapshotResolver(lookup, FakePersonNameLookup(), metrics),
)

private fun create(
    recipient: ArbeidsgiverRecipient = AltinnResource("producer-resource"),
    tag: String? = "producer-tag",
    visibleUntil: Instant? = null,
    link: String? = "https://nav.no/lenke",
    sakstilknytning: Sakstilknytning? = null,
    sakstype: Sakstype? = null,
    saksstatus: Saksstatus? = null,
) = ArbeidsgivervarselCreate(
    TEST_ORGNUMMER,
    recipient,
    tag,
    "Tekst",
    link,
    sakstilknytning = sakstilknytning,
    visibleUntil = visibleUntil,
    sakstype = sakstype,
    saksstatus = saksstatus,
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
    private val response: ArbeidsgiverNotificationResponse = ArbeidsgiverNotificationResponse.Published,
) : ArbeidsgiverNotificationPublisher {
    val requests = mutableListOf<ArbeidsgiverNotificationRequest>()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        requests += request
        return response
    }
}
