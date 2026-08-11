package no.nav.budstikka.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.TestApplication
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.NoDispatchMetrics
import no.nav.budstikka.application.port.StoredCreateDelivery
import no.nav.budstikka.bootstrap.gateModule
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.DittSykefravaerInactivate
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeathGate
import no.nav.budstikka.domain.decision.DeathLookup
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.decision.ReservationLookup
import no.nav.budstikka.domain.decision.SendingWindowGate
import no.nav.budstikka.domain.foundation.calendar.NorwegianRodeDager
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.FakeDocumentDistributor
import no.nav.budstikka.fakes.FakeReservationLookup
import no.nav.budstikka.fakes.FakeTransactionRunner
import no.nav.budstikka.fakes.RecordingDispatchMetrics
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.TEST_SYKMELDT_2
import no.nav.budstikka.fakes.deadLookupFor
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.worker.BackgroundLoop
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val osloTz = TimeZone.of("Europe/Oslo")

fun Application.configureTestDeps() {
    dependencies {
        provide<DeathLookup> {
            FakeDeathLookup()
        }
        provide<DocumentDistributor> {
            FakeDocumentDistributor()
        }
        provide<ReservationLookup> {
            FakeReservationLookup()
        }

        gateModule()
    }
}

class InboxMessageWorkerTest :
    FunSpec({
        lateinit var testApplication: TestApplication
        lateinit var application: Application
        lateinit var decisionProcess: DecisionProcess

        beforeSpec {
            testApplication =
                TestApplication {
                    application {
                        configureTestDeps()
                        application = this
                    }
                }

            testApplication.start()

            // resolve the decision rules from the application DI context to mitigate
            // forgetting any rules in production code. This ensures that the test uses the same rules as the application.
            val rules = application.dependencies.resolve<List<DecisionRule>>()
            decisionProcess = DecisionProcess(rules)
        }

        afterSpec {
            testApplication.stop()
        }

        test("runOnce marks claimed messages processed") {
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId)),
                )
            val worker = workerWith(repository, batchSize = 10, decisionProcess = decisionProcess)

            worker.runOnce()

            repository.lastPollLimit shouldBe 10
            repository.processedEventIds.shouldContainExactly(eventId)
            repository.failedMessages.shouldBeEmpty()
        }

        test("valid dispatch carries reference on MDC for cross-event (CREATE->INACTIVATE) correlation") {
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId, reference = "ref-1")),
                )

            val logbackLogger = LoggerFactory.getLogger(InboxMessageWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, decisionProcess = decisionProcess).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            val event = appender.list.single { it.formattedMessage.contains("Inbox message processed") }
            event.formattedMessage shouldContain "result=PROCESSED"
            event.formattedMessage shouldContain "${MdcKeys.DELIVERY_COUNT}=1"
            event.mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe eventId.toString()
            event.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-1"
        }

        test("runOnce records inbox metrics for processed outcomes") {
            val eventId1 = UUID.fromString("00000000-0000-0000-0000-000000000003")
            val eventId2 = UUID.fromString("00000000-0000-0000-0000-000000000004")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId1), inboxMessage(eventId2)),
                )
            val metrics = RecordingDispatchMetrics()

            workerWith(repository, metrics = metrics, decisionProcess = decisionProcess).runOnce()

            metrics.inboxClaimed.get() shouldBe 2
            metrics.inboxProcessed.get() shouldBe 2
            metrics.inboxFailed.get() shouldBe 0
            metrics.inboxEmptyPolls.get() shouldBe 0
        }

        test("missing FERDIGSTILL match is processed as a no-op with a low-cardinality metric") {
            val metrics = RecordingDispatchMetrics()
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.fromString("00000000-0000-0000-0000-000000000006"),
                                reference = "no-match-ref",
                                content = BrukervarselInactivate("no-match-ref", TEST_SYKMELDT),
                            ),
                        ),
                )

            val logbackLogger = LoggerFactory.getLogger(InboxMessageWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, metrics = metrics, decisionProcess = decisionProcess).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            metrics.inboxProcessed.get() shouldBe 1
            metrics.ferdigstillWithoutMatch.get() shouldBe 1
            metrics.ferdigstillWithoutSupportedRuntimeChannel.get() shouldBe 0
            repository.processedEventIds.shouldHaveSize(1)
            val log = appender.list.single { it.formattedMessage.contains("Ferdigstill processed without matching") }
            log.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe null
            log.formattedMessage shouldNotContain TEST_SYKMELDT.value
            log.formattedMessage shouldNotContain MdcKeys.DELIVERY_COUNT
        }

        test("unsupported Ditt Sykefravær FERDIGSTILL is processed as a no-op with a runtime-channel metric") {
            val metrics = RecordingDispatchMetrics()
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.fromString("00000000-0000-0000-0000-000000000007"),
                                reference = "unsupported-ref",
                                content = DittSykefravaerInactivate("unsupported-ref", TEST_SYKMELDT),
                            ),
                        ),
                )

            workerWith(repository, metrics = metrics, decisionProcess = decisionProcess).runOnce()

            metrics.inboxProcessed.get() shouldBe 1
            metrics.ferdigstillWithoutMatch.get() shouldBe 0
            metrics.ferdigstillWithoutSupportedRuntimeChannel.get() shouldBe 1
            repository.processedEventIds.shouldHaveSize(1)
        }

        test("storage-derived FERDIGSTILL logs the materialized INAKTIVER delivery count") {
            val reference = "stored-create-ref"
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.fromString("00000000-0000-0000-0000-000000000008"),
                                reference = reference,
                                content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                            ),
                        ),
                )
            val deliveries =
                RecordingDeliveryRepository(
                    storedCreate =
                        StoredCreateDelivery(
                            createExternalId = null,
                            reference = reference,
                            channel = Channel.BRUKERVARSEL,
                            recipient = Recipient.Person(TEST_SYKMELDT),
                            payload = BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "stored create"),
                        ),
                )

            val logbackLogger = LoggerFactory.getLogger(InboxMessageWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, decisionProcess = decisionProcess, deliveryRepository = deliveries).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            deliveries.saved.single().second shouldHaveSize 1
            val log = appender.list.single { it.formattedMessage.contains("Inbox message processed") }
            log.formattedMessage shouldContain "${MdcKeys.DELIVERY_COUNT}=1"
        }

        test("invalid stored CREATE is a PII-free terminal FERDIGSTILL no-op with its own metric") {
            val reference = "invalid-stored-create-ref"
            val waitingCreateEventId = UUID.fromString("00000000-0000-0000-0000-000000000009")
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.fromString("00000000-0000-0000-0000-000000000011"),
                                reference = reference,
                                content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                            ),
                        ),
                    waitingCreateEventIds = listOf(waitingCreateEventId),
                )
            val deliveries =
                RecordingDeliveryRepository(
                    storedCreate =
                        StoredCreateDelivery(
                            createExternalId = null,
                            reference = reference,
                            channel = Channel.BRUKERVARSEL,
                            recipient = Recipient.Person(TEST_SYKMELDT),
                            payload =
                                BrukervarselCreate(
                                    TEST_SYKMELDT_2,
                                    Varseltype.BESKJED,
                                    "schema drift",
                                ),
                        ),
                )
            val metrics = RecordingDispatchMetrics()

            val logbackLogger = LoggerFactory.getLogger(InboxMessageWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(
                    repository,
                    metrics = metrics,
                    decisionProcess = decisionProcess,
                    deliveryRepository = deliveries,
                ).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            metrics.inboxProcessed.get() shouldBe 1
            metrics.ferdigstillWithInvalidStoredCreate.get() shouldBe 1
            metrics.ferdigstillWithoutMatch.get() shouldBe 0
            metrics.ferdigstillWithoutSupportedRuntimeChannel.get() shouldBe 0
            repository.processedEventIds shouldContainExactly
                listOf(UUID.fromString("00000000-0000-0000-0000-000000000011"), waitingCreateEventId)
            deliveries.saved.shouldBeEmpty()
            val log = appender.list.single { it.formattedMessage.contains("invalid stored create") }
            log.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe null
            log.formattedMessage shouldNotContain TEST_SYKMELDT.value
            log.formattedMessage shouldNotContain TEST_SYKMELDT_2.value
            log.formattedMessage shouldNotContain MdcKeys.DELIVERY_COUNT
        }

        test("runOnce records a dropped metric when a gate drops the message") {
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000005")
            val deadContent = BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "text")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId, content = deadContent)),
                )
            val metrics = RecordingDispatchMetrics()

            workerWith(
                repository,
                metrics = metrics,
                decisionProcess = DecisionProcess(listOf(DeathGate(deadLookupFor(TEST_SYKMELDT)))),
            ).runOnce()

            metrics.inboxDropped[DropReason.DEAD]?.get() shouldBe 1
            metrics.inboxProcessed.get() shouldBe 0
        }

        test("runOnce records an empty poll when nothing is claimed") {
            val repository = PollingInboxMessageRepository(messages = emptyList())
            val metrics = RecordingDispatchMetrics()

            workerWith(repository, metrics = metrics, decisionProcess = decisionProcess).runOnce()

            metrics.inboxEmptyPolls.get() shouldBe 1
            metrics.inboxClaimed.get() shouldBe 0
        }

        test("runOnce stops draining when the lease budget is exhausted") {
            val clock = MutableClock(Instant.fromEpochSeconds(0))
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(UUID.randomUUID()), inboxMessage(UUID.randomUUID())),
                    onPoll = {
                        // advance the clock to exhaust the lease budget after the first poll
                        clock.current += 1.milliseconds
                    },
                )
            val worker =
                workerWith(
                    repository,
                    leaseDuration = 1.milliseconds,
                    leaseBudgetFraction = 0.1,
                    clock = clock,
                    decisionProcess = decisionProcess,
                )

            worker.runOnce()

            repository.processedEventIds.shouldBeEmpty()
            repository.failedMessages.shouldBeEmpty()
        }

        test("closing the composed loop stops polling") {
            val polled = CountDownLatch(2)
            val repository =
                PollingInboxMessageRepository(
                    messages = emptyList(),
                ) {
                    polled.countDown()
                }
            val worker =
                workerWith(
                    repository,
                    batchSize = LeaseDrainConfig.DEFAULT_BATCH_SIZE,
                    decisionProcess = decisionProcess,
                )
            val loop = BackgroundLoop("inbox-message-worker", 10.milliseconds, iteration = worker::runOnce)

            loop.start()
            polled.await(5, TimeUnit.SECONDS) shouldBe true
            loop.close()

            val pollCountAfterClose = repository.pollCount.get()
            Thread.sleep(100)
            repository.pollCount.get() shouldBe pollCountAfterClose
        }

        test("runOnce marks messages outside sending window as waiting with nextRetry in the future") {
            val now = NorwegianRodeDager.easterSunday(2025).atTime(3, 0).toInstant(osloTz)
            val clock = MutableClock(now)
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.randomUUID(),
                                reference = "ref-1",
                                content =
                                    BrukervarselCreate(
                                        TEST_SYKMELDT,
                                        Varseltype.OPPGAVE,
                                        "test",
                                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                                    ),
                            ),
                        ),
                )
            val worker =
                workerWith(
                    repository,
                    decisionProcess = DecisionProcess(listOf(SendingWindowGate(clock))),
                    clock = clock,
                )

            worker.runOnce()

            repository.waitingMessages.size shouldBe 1
            val (_, nextRetry) = repository.waitingMessages.values.single()

            (nextRetry > now) shouldBe true
        }

        test("runOnce persistst reason for waiting messages") {
            val now = NorwegianRodeDager.easterSunday(2025).atTime(3, 0).toInstant(osloTz)
            val clock = MutableClock(now)
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.randomUUID(),
                                reference = "ref-1",
                                content =
                                    BrukervarselCreate(
                                        TEST_SYKMELDT,
                                        Varseltype.OPPGAVE,
                                        "test",
                                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                                    ),
                            ),
                        ),
                )
            val worker =
                workerWith(
                    repository,
                    decisionProcess = DecisionProcess(listOf(SendingWindowGate(clock))),
                    clock = clock,
                )

            worker.runOnce()

            repository.waitingMessages.size shouldBe 1
            val (reason, _) = repository.waitingMessages.values.single()

            reason shouldContain "Closed Sunday"
        }

        test("Claim picks up messages in waiting state after nextRetry has passed") {
            val now = MutableClock(LocalDateTime(2026, Month.AUGUST, 2, 23, 0).toInstant(osloTz))
            val clock = MutableClock(now.current)
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            inboxMessage(
                                UUID.randomUUID(),
                                reference = "ref-1",
                                content =
                                    BrukervarselCreate(
                                        TEST_SYKMELDT,
                                        Varseltype.OPPGAVE,
                                        "test",
                                        sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                                    ),
                            ),
                        ),
                )
            val worker =
                workerWith(
                    repository,
                    decisionProcess = DecisionProcess(listOf(SendingWindowGate(clock))),
                    clock = clock,
                )

            // first runOnce marks the message as waiting
            worker.runOnce()
            repository.waitingMessages.size shouldBe 1

            // advance the clock to after nextRetry
            clock.current += 10.hours

            // second runOnce should pick up the message again
            worker.runOnce()
            repository.processedEventIds.size shouldBe 1
        }
    })

private fun workerWith(
    repository: PollingInboxMessageRepository,
    batchSize: Int = 10,
    leaseDuration: Duration = 5.minutes,
    leaseBudgetFraction: Double = 0.8,
    maxConsecutiveItemFailures: Int = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
    clock: Clock = Clock.System,
    metrics: DispatchMetrics = NoDispatchMetrics,
    decisionProcess: DecisionProcess,
    deliveryRepository: RecordingDeliveryRepository = RecordingDeliveryRepository(),
): InboxMessageWorker =
    InboxMessageWorker(
        repository = repository,
        effectuator =
            EffectuateDecision(
                transactionRunner = FakeTransactionRunner(),
                inboxMessageRepository = repository,
                deliveryRepository = deliveryRepository,
            ),
        decisionProcess = decisionProcess,
        drainer =
            LeaseBudgetDrainer(
                leaseBudgetFraction = leaseBudgetFraction,
                maxConsecutiveItemFailures = maxConsecutiveItemFailures,
                clock = clock,
            ),
        config =
            LeaseDrainConfig(
                interval = 1.seconds,
                batchSize = batchSize,
                leaseDuration = leaseDuration,
                leaseBudgetFraction = leaseBudgetFraction,
                maxAttempts = LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
                maxConsecutiveItemFailures = maxConsecutiveItemFailures,
            ),
        metrics = metrics,
    )

private class PollingInboxMessageRepository(
    private val messages: List<InboxMessage>,
    private val waitingCreateEventIds: List<UUID> = emptyList(),
    private val onPoll: () -> Unit = {},
) : InboxMessageRepository {
    var lastPollLimit: Int? = null
        private set
    val pollCount = AtomicInteger(0)
    val processedEventIds = mutableListOf<UUID>()
    val failedMessages = mutableListOf<Pair<UUID, String>>()
    val waitingMessages = mutableMapOf<UUID, Pair<String, Instant>>()

    override suspend fun saveBatch(messages: List<InboxMessage>) = Unit

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage> {
        lastPollLimit = limit
        pollCount.incrementAndGet()
        onPoll()
        return messages
    }

    val attemptedEventIds = mutableListOf<UUID>()

    override suspend fun beginAttempt(
        eventId: UUID,
        maxAttempts: Int,
    ): Boolean {
        attemptedEventIds += eventId
        return true
    }

    override fun markProcessedInTransaction(eventId: UUID): Boolean {
        processedEventIds += eventId
        return true
    }

    override fun lockClaimedForEffectuationInTransaction(eventId: UUID): Boolean = true

    override fun lockWaitingCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID> = waitingCreateEventIds

    override fun markWaitingCreateProcessedInTransaction(eventId: UUID): Boolean = markProcessedInTransaction(eventId)

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = true

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean {
        failedMessages += eventId to reason
        return true
    }

    override fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean {
        waitingMessages += eventId to (reason to nextRetry)
        return true
    }
}

private class RecordingDeliveryRepository(
    private val storedCreate: StoredCreateDelivery? = null,
) : DeliveryRepository {
    val saved = mutableListOf<Pair<UUID, List<DeliveryDraft>>>()

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        saved += inboxEventId to draft
    }

    override fun findCreateForFerdigstillInTransaction(match: FerdigstillMatch) = storedCreate

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): List<ClaimedDelivery> = emptyList()

    override suspend fun beginAttempt(
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean = true

    override suspend fun markSent(deliveryId: UUID): Boolean = true

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean = true
}
