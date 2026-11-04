package no.nav.budstikka.application.inbox

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakConfig
import no.nav.budstikka.application.delivery.Arbeidsgiversaker
import no.nav.budstikka.application.delivery.ArbeidsgivervarselChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.LegacyDeliverySnapshotResolver
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.decision.toDeliveryDraft
import no.nav.budstikka.fakes.FakeArbeidsgiverNotificationPublisher
import no.nav.budstikka.fakes.FakeArbeidsgiversakPublisher
import no.nav.budstikka.fakes.TEST_LEDERRELASJON_ID
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.PostgresTransactionRunner
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.delivery.PostgresDeliveryRepository
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.PostgresInboxMessageRepository
import no.nav.budstikka.testsupport.renderedLogData
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EffectuateDecisionIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun effectuator(): Pair<EffectuateDecision, PostgresInboxMessageRepository> {
            val inbox = PostgresInboxMessageRepository(fixture.database)
            val effectuate =
                EffectuateDecision(
                    transactionRunner = PostgresTransactionRunner(fixture.database),
                    inboxMessageRepository = inbox,
                    deliveryRepository = PostgresDeliveryRepository(fixture.database),
                )
            return effectuate to inbox
        }

        suspend fun deliveryCount(inboxEventId: UUID): Long =
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.count()
            }

        suspend fun inboxState(eventId: UUID): String =
            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()[InboxMessageTable.state]
            }

        test("Processed commits delivery rows and inbox PROCESSED atomically") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            val claim = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim

            effectuate.effectuate(claim, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1")))) shouldBe true

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }

        test("Decision snapshot roundtrips atomically and freezes notification, sak generations, link and status through reclaim") {
            val (effectuate, inbox) = effectuator()
            val deliveries = PostgresDeliveryRepository(fixture.database)
            val eventId = UUID.randomUUID()
            var leader = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), listOf("snapshot@example.test"), TEST_LEDERRELASJON_ID)
            var name = PersonName("Synthetic", "Middle", "Snapshot")
            var leaderCalls = 0
            var nameCalls = 0
            val leaders =
                NarmesteLederLookup { _, _ ->
                    leaderCalls++
                    leader
                }

            val names =
                object : PersonNameLookup {
                    override suspend fun findName(ident: PersonIdentifier): PersonName {
                        nameCalls++
                        return name
                    }
                }
            val enrichment = ArbeidsgiverDecisionEnrichment(leaders, names, NoDeliveryMetrics)
            val create =
                ArbeidsgivervarselCreate(
                    TEST_ORGNUMMER,
                    NarmesteLeder(TEST_SYKMELDT, NarmesteLederExternalVarsling("title", "body")),
                    text = "text",
                    sakstype = Sakstype.DIALOGMOTE,
                    saksstatus = Saksstatus.FERDIG,
                )
            inbox.saveBatch(
                listOf(
                    no.nav.budstikka.application.port
                        .InboxMessage(eventId, "snapshot", create),
                ),
            )
            val claim = inbox.claim(1, lease, 10).single().claim
            val decision = DecisionProcess(emptyList(), enrichment::enrich).process(Dispatch("snapshot", create))
            effectuate.effectuate(claim, decision) shouldBe true
            inboxState(eventId) shouldBe "PROCESSED"
            val delivery = deliveries.claim(1, lease, 10, setOf(Channel.ARBEIDSGIVERVARSEL)).single()
            delivery.snapshot shouldBe
                decision
                    .shouldBeInstanceOf<Decision.Processed>()
                    .deliveries
                    .single()
                    .snapshot
            delivery.payload shouldBe create
            leader = NarmesteLederRelasjon(PersonIdentifier("3".repeat(11)), listOf("changed@example.test"), UUID.randomUUID())
            name = PersonName("Changed", null, "Register")
            val missing = ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke")
            val published = ArbeidsgiverNotificationResponse.Published
            val sakPublisher =
                FakeArbeidsgiversakPublisher(
                    missing,
                    missing,
                    ArbeidsgiverNotificationResponse.SakDeleted("SakErSlettet"),
                    missing,
                    missing,
                    published,
                    published,
                    missing,
                    missing,
                    published,
                    published,
                )
            val publisher = FakeArbeidsgiverNotificationPublisher()
            val handler =
                ArbeidsgivervarselChannelHandler(
                    publisher,
                    Arbeidsgiversaker(sakPublisher, ArbeidsgiversakConfig("https://www.nav.no/arbeidsgiver/sykmeldte")),
                    LegacyDeliverySnapshotResolver(leaders, names, NoDeliveryMetrics),
                )
            deliveries.beginAttempt(delivery.claim, 10) shouldBe true
            shouldThrow<RuntimeException> { handler.handle(delivery) }
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq delivery.id }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
            val retry = deliveries.claim(1, lease, 10, setOf(Channel.ARBEIDSGIVERVARSEL)).single()
            deliveries.beginAttempt(retry.claim, 10) shouldBe true
            handler.handle(retry) shouldBe DeliveryOutcome.Sent
            deliveries.markSent(retry.claim) shouldBe true
            leaderCalls shouldBe 1
            nameCalls shouldBe 1
            publisher.requests.size shouldBe 2
            publisher.requests[1] shouldBe publisher.requests[0]
            publisher.requests.first().apply {
                lenke shouldBe "https://www.nav.no/arbeidsgiver/sykmeldte/$TEST_LEDERRELASJON_ID"
                grupperingsid shouldBe "DIALOGMOTE:$TEST_LEDERRELASJON_ID:2"
                val recipient = recipient.shouldBeInstanceOf<ArbeidsgiverNotificationRecipient.NarmesteLeder>()
                recipient.narmesteLederFnr shouldBe PersonIdentifier("2".repeat(11))
                recipient.externalVarsling?.epostadresser shouldBe listOf("snapshot@example.test")
            }
            sakPublisher.sakCreates.size shouldBe 2
            sakPublisher.sakCreates.forEach {
                it.title shouldBe "Dialogmøte med Synthetic Middle Snapshot"
                it.link shouldBe publisher.requests.first().lenke
            }
            sakPublisher.saksstatuser.size shouldBe 2
            sakPublisher.saksstatuser[1] shouldBe sakPublisher.saksstatuser[0]
        }

        test("kalender Decision snapshots roundtrip atomically for create, email, status and lookup-free updates") {
            val (effectuate, inbox) = effectuator()
            val repository = PostgresDeliveryRepository(fixture.database)
            val leader = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), listOf("snapshot@example.test"), TEST_LEDERRELASJON_ID)
            val name = PersonName("Synthetic", null, "Calendar")
            val enrichment =
                ArbeidsgiverDecisionEnrichment(
                    NarmesteLederLookup { _, _ -> leader },
                    object : PersonNameLookup {
                        override suspend fun findName(ident: PersonIdentifier): PersonName = name
                    },
                    NoDeliveryMetrics,
                )
            val recipient = NarmesteLeder(TEST_SYKMELDT)
            val create =
                KalenderavtaleCreate(
                    TEST_ORGNUMMER,
                    recipient,
                    Sakstype.DIALOGMOTE,
                    "text",
                    "https://nav.no/calendar",
                    Instant.parse("2026-12-01T10:00:00Z"),
                )
            val update = KalenderavtaleUpdate(TEST_ORGNUMMER, recipient, Sakstype.DIALOGMOTE, text = "updated")
            val variants =
                listOf(
                    create to DeliverySnapshot.Resolved(leader, name),
                    update to DeliverySnapshot.Resolved(),
                    update.copy(recipient = recipient.copy(externalVarsling = NarmesteLederExternalVarsling("title", "body"))) to
                        DeliverySnapshot.Resolved(leader),
                    update.copy(saksstatus = Saksstatus.FERDIG) to DeliverySnapshot.Resolved(leader, name),
                )
            variants.forEach { (content, expected) ->
                val eventId = UUID.randomUUID()
                inbox.saveBatch(
                    listOf(
                        no.nav.budstikka.application.port
                            .InboxMessage(eventId, "calendar", content),
                    ),
                )
                val claim = inbox.claim(1, lease, 10).single().claim
                val decision = DecisionProcess(emptyList(), enrichment::enrich).process(Dispatch("calendar", content))
                effectuate.effectuate(claim, decision) shouldBe true
                inboxState(eventId) shouldBe "PROCESSED"
                deliveryCount(eventId) shouldBe 1
                val delivery = repository.claim(1, lease, 10, setOf(Channel.KALENDERAVTALE)).single()
                delivery.snapshot shouldBe expected
                delivery.payload shouldBe content
                fixture.database.transact {
                    val row = DeliveryTable.selectAll().where { DeliveryTable.id eq delivery.id }.single()
                    row[DeliveryTable.recipientType] shouldBe "VIRKSOMHET"
                    row[DeliveryTable.recipientId] shouldBe TEST_ORGNUMMER.value
                }
                repository.markSent(delivery.claim) shouldBe true
            }
        }

        test("resolved null names roundtrip for leader and Altinn and never enter legacy lookups") {
            val (effectuate, inbox) = effectuator()
            val repository = PostgresDeliveryRepository(fixture.database)
            val leader = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
            var nameCalls = 0
            val names =
                object : PersonNameLookup {
                    override suspend fun findName(ident: PersonIdentifier): PersonName? {
                        nameCalls++
                        return null
                    }
                }
            val enrichment = ArbeidsgiverDecisionEnrichment(NarmesteLederLookup { _, _ -> leader }, names, NoDeliveryMetrics)
            val forbiddenNames =
                object : PersonNameLookup {
                    override suspend fun findName(ident: PersonIdentifier): PersonName? = error("Delivery must not look up names")
                }
            val legacy =
                LegacyDeliverySnapshotResolver(
                    NarmesteLederLookup { _, _ -> error("Delivery must not look up leaders") },
                    forbiddenNames,
                    NoDeliveryMetrics,
                )
            val recipients: List<ArbeidsgiverRecipient> =
                listOf(
                    NarmesteLeder(TEST_SYKMELDT),
                    AltinnResource("resource", sykmeldt = TEST_SYKMELDT, sakId = "sak-1"),
                )
            for (recipient in recipients) {
                val eventId = UUID.randomUUID()
                val content =
                    ArbeidsgivervarselCreate(
                        TEST_ORGNUMMER,
                        recipient,
                        text = "text",
                        link = "https://altinn.no/path",
                        sakstype = Sakstype.DIALOGMOTE,
                    )
                inbox.saveBatch(
                    listOf(
                        no.nav.budstikka.application.port
                            .InboxMessage(eventId, "null-name", content),
                    ),
                )
                val claim = inbox.claim(1, lease, 10).single().claim
                effectuate.effectuate(
                    claim,
                    DecisionProcess(emptyList(), enrichment::enrich).process(Dispatch("null-name", content)),
                ) shouldBe
                    true
                val delivery = repository.claim(1, lease, 10, setOf(Channel.ARBEIDSGIVERVARSEL)).single()
                delivery.snapshot.shouldBeInstanceOf<DeliverySnapshot.Resolved>().name shouldBe null
                val missing = ArbeidsgiverNotificationResponse.NotYetAvailable("SakFinnesIkke")
                val saker = FakeArbeidsgiversakPublisher(missing, missing)
                ArbeidsgivervarselChannelHandler(
                    FakeArbeidsgiverNotificationPublisher(),
                    Arbeidsgiversaker(saker, ArbeidsgiversakConfig("https://nav.no/arbeidsgiver/sykmeldte")),
                    legacy,
                ).handle(delivery) shouldBe DeliveryOutcome.Sent
                saker.sakCreates.single().title shouldBe "Dialogmøte"
                repository.markSent(delivery.claim) shouldBe true
            }
            nameCalls shouldBe 2
        }

        listOf(
            ArbeidsgivervarselCreate(TEST_ORGNUMMER, NarmesteLeder(TEST_SYKMELDT), text = "text", sakstype = Sakstype.DIALOGMOTE),
            KalenderavtaleCreate(
                TEST_ORGNUMMER,
                NarmesteLeder(TEST_SYKMELDT),
                Sakstype.DIALOGMOTE,
                "text",
                "https://nav.no/calendar",
                Instant.parse("2026-12-01T10:00:00Z"),
            ),
            KalenderavtaleUpdate(
                TEST_ORGNUMMER,
                NarmesteLeder(TEST_SYKMELDT),
                Sakstype.DIALOGMOTE,
                saksstatus = Saksstatus.FERDIG,
            ),
        ).forEach { content ->
            test("snapshot CHECK failure rolls back inbox and delivery without sensitive diagnostics: ${content::class.simpleName}") {
                val (effectuate, inbox) = effectuator()
                val eventId = UUID.randomUUID()
                inbox.saveBatch(
                    listOf(
                        no.nav.budstikka.application.port
                            .InboxMessage(eventId, "privacy", content),
                    ),
                )
                val claim = inbox.claim(1, lease, 10).single().claim
                val leader = NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), listOf("private@example.test"), TEST_LEDERRELASJON_ID)
                val snapshot = DeliverySnapshot.Resolved(leader, PersonName("SyntheticPrivate", null, "SnapshotPrivate"))
                val draft = content.toDeliveryDraft("privacy").copy(snapshot = snapshot.copy(leader = null))
                val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                logger.addAppender(appender)
                val error =
                    try {
                        shouldThrow<IllegalStateException> {
                            effectuate.effectuate(
                                claim,
                                Decision.Processed(listOf(content.toDeliveryDraft("privacy").copy(snapshot = snapshot), draft)),
                            )
                        }
                    } finally {
                        logger.detachAppender(appender)
                        appender.stop()
                    }
                val diagnostics =
                    appender.list.joinToString { it.renderedLogData() } +
                        generateSequence<Throwable>(error) { it.cause }.joinToString { it.toString() } +
                        snapshot.toString() + leader.toString() + snapshot.name.toString() + draft.toString()
                listOf(
                    "SyntheticPrivate",
                    "SnapshotPrivate",
                    "private@example.test",
                    "2".repeat(11),
                    TEST_LEDERRELASJON_ID.toString(),
                ).forEach {
                    diagnostics shouldNotContain it
                }
                inboxState(eventId) shouldBe "CLAIMED"
                deliveryCount(eventId) shouldBe 0
            }
        }

        listOf(
            ArbeidsgivervarselCreate(
                TEST_ORGNUMMER,
                AltinnResource("resource", sykmeldt = TEST_SYKMELDT, sakId = "sak-1"),
                text = "text",
                link = "https://altinn.no/path",
                sakstype = Sakstype.DIALOGMOTE,
            ),
            KalenderavtaleCreate(
                TEST_ORGNUMMER,
                NarmesteLeder(TEST_SYKMELDT),
                Sakstype.DIALOGMOTE,
                "text",
                "https://nav.no/calendar",
                Instant.parse("2026-12-01T10:00:00Z"),
            ),
            KalenderavtaleUpdate(
                TEST_ORGNUMMER,
                NarmesteLeder(TEST_SYKMELDT),
                Sakstype.DIALOGMOTE,
                saksstatus = Saksstatus.FERDIG,
            ),
        ).forEach { content ->
            test("transient PDL failure leaves no delivery or processed inbox: ${content::class.simpleName}") {
                val (effectuate, inbox) = effectuator()
                val eventId = UUID.randomUUID()
                inbox.saveBatch(
                    listOf(
                        no.nav.budstikka.application.port
                            .InboxMessage(eventId, "pdl-retry", content),
                    ),
                )
                val claim = inbox.claim(1, lease, 10).single().claim
                val names =
                    object : PersonNameLookup {
                        override suspend fun findName(ident: PersonIdentifier): PersonName? = error("private register response")
                    }
                val enrichment =
                    ArbeidsgiverDecisionEnrichment(
                        NarmesteLederLookup { _, _ ->
                            NarmesteLederRelasjon(PersonIdentifier("2".repeat(11)), emptyList(), TEST_LEDERRELASJON_ID)
                        },
                        names,
                        NoDeliveryMetrics,
                    )
                shouldThrow<IllegalStateException> {
                    effectuate.effectuate(claim, DecisionProcess(emptyList(), enrichment::enrich).process(Dispatch("pdl-retry", content)))
                }
                inboxState(eventId) shouldBe "CLAIMED"
                deliveryCount(eventId) shouldBe 0
            }
        }

        test("Failed writes no delivery rows and inbox FAILED with reason") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            val claim = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim

            effectuate.effectuate(claim, Decision.Failed("boom")) shouldBe true

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "FAILED"
        }

        test("Dropped writes no delivery rows and inbox DROPPED") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            val claim = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim

            effectuate.effectuate(claim, Decision.Dropped(DropReason.DEAD)) shouldBe true

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "DROPPED"
        }

        test("a second Processed after the CAS is lost writes no extra delivery rows") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            val claim = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim

            effectuate.effectuate(claim, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1")))) shouldBe true
            effectuate.effectuate(claim, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1")))) shouldBe false

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }
    })
