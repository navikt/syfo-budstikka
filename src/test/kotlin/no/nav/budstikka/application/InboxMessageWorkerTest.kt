package no.nav.budstikka.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.NoDispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeathGate
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.FakeDeathLookup
import no.nav.budstikka.fakes.FakeTransactionRunner
import no.nav.budstikka.fakes.RecordingDispatchMetrics
import no.nav.budstikka.fakes.TEST_SYKMELDT
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Meldingene er hydrert ved ingest (ADR 0008), så workeren dekoder ikke lenger payload og kan aldri
 * feile på `SerializationException`. Parse-taksonomien (korrupt payload → dead-letter) testes derfor i
 * `InboxHandlerTest`, ikke her. Workeren beslutter (via [DecisionProcess]) og effektuerer.
 */
class InboxMessageWorkerTest :
    FunSpec({
        test("runOnce marks claimed messages processed") {
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId)),
                )
            val worker = workerWith(repository, batchSize = 10)

            worker.runOnce()

            repository.lastPollLimit shouldBe 10
            repository.processedEventIds.shouldContainExactly(eventId)
            repository.failedMessages.shouldBeEmpty()
        }

        test("valid dispatch carries reference on MDC for cross-event (OPPRETT->FERDIGSTILL) correlation") {
            val eventId = UUID.fromString("00000000-0000-0000-0000-000000000010")
            val repository =
                PollingInboxMessageRepository(
                    messages = listOf(inboxMessage(eventId, reference = "ref-1")),
                )

            val logbackLogger = LoggerFactory.getLogger(InboxMessageWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            val event = appender.list.single { it.formattedMessage.contains("Inbox message processed") }
            event.formattedMessage shouldContain "result=PROCESSED"
            event.formattedMessage shouldContain "deliveryCount=1"
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

            workerWith(repository, metrics = metrics).runOnce()

            metrics.inboxClaimed.get() shouldBe 2
            metrics.inboxProcessed.get() shouldBe 2
            metrics.inboxFailed.get() shouldBe 0
            metrics.inboxEmptyPolls.get() shouldBe 0
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

            workerWith(repository, metrics = metrics).runOnce()

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
            val worker = workerWith(repository, batchSize = LeaseDrainConfig.DEFAULT_BATCH_SIZE)
            val loop = BackgroundLoop("inbox-message-worker", 10.milliseconds, iteration = worker::runOnce)

            loop.start()
            polled.await(5, TimeUnit.SECONDS) shouldBe true
            loop.close()

            val pollCountAfterClose = repository.pollCount.get()
            Thread.sleep(100)
            repository.pollCount.get() shouldBe pollCountAfterClose
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
    decisionProcess: DecisionProcess = DecisionProcess(listOf(DeathGate(FakeDeathLookup()))),
): InboxMessageWorker =
    InboxMessageWorker(
        repository = repository,
        effectuator =
            EffectuateDecision(
                transactionRunner = FakeTransactionRunner(),
                inboxMessageRepository = repository,
                deliveryRepository = RecordingDeliveryRepository(),
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
    private val onPoll: () -> Unit = {},
) : InboxMessageRepository {
    var lastPollLimit: Int? = null
        private set
    val pollCount = AtomicInteger(0)
    val processedEventIds = mutableListOf<UUID>()
    val failedMessages = mutableListOf<Pair<UUID, String>>()

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

    override fun markProcessedInTransaction(eventId: UUID): Boolean {
        processedEventIds += eventId
        return true
    }

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
}

private class RecordingDeliveryRepository : DeliveryRepository {
    val saved = mutableListOf<Pair<UUID, List<DeliveryDraft>>>()

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        saved += inboxEventId to draft
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): List<ClaimedDelivery> = emptyList()

    override suspend fun markSent(deliveryId: UUID): Boolean = true

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean = true
}
