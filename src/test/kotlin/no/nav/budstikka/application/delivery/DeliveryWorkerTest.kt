package no.nav.budstikka.application.delivery

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryClaim
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.worker.AlreadyLoggedWorkerFailure
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.application.worker.LeaseWorkerLogEvents
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.LedervarselCreate
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.worker.BackgroundLoop
import no.nav.budstikka.testsupport.structuredFields
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
import kotlin.time.Instant.Companion.fromEpochMilliseconds

class DeliveryWorkerTest :
    FunSpec({
        test("a delivery waiting until Saturday is postponed without spending a send attempt while ongoing and closing still send") {
            val scheduled =
                validMicrofrontendDelivery(UUID.randomUUID()).copy(
                    channel = Channel.BRUKERVARSEL,
                    payload = BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "Synthetic task"),
                )
            val ongoing =
                scheduled.copy(
                    id = UUID.randomUUID(),
                    payload = (scheduled.payload as BrukervarselCreate).copy(sendingWindow = SendingWindow.ONGOING),
                )
            val closing = scheduled.copy(id = UUID.randomUUID(), payload = BrukervarselInactivate("ref-1", TEST_SYKMELDT))
            val employer =
                scheduled.copy(
                    id = UUID.randomUUID(),
                    channel = Channel.ARBEIDSGIVERVARSEL,
                    payload =
                        ArbeidsgivervarselCreate(
                            TEST_ORGNUMMER,
                            NarmesteLeder(TEST_SYKMELDT),
                            tag = "Synthetic",
                            text = "Synthetic task",
                            link = "https://nav.no/synthetic",
                        ),
                )
            val leader =
                scheduled.copy(
                    id = UUID.randomUUID(),
                    channel = Channel.LEDERVARSEL,
                    payload =
                        LedervarselCreate(
                            TEST_SYKMELDT,
                            TEST_ORGNUMMER,
                            Oppgavetype.DIALOGMOTE_INNKALLING,
                            "Synthetic task",
                            sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
                        ),
                )
            val repository = PollingDeliveryRepository(listOf(scheduled, employer, leader, ongoing, closing))
            val metrics = RecordingDeliveryMetrics()
            val handled = mutableListOf<UUID>()
            val handler =
                object : ChannelHandler {
                    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
                        handled += delivery.id
                        return DeliveryOutcome.Sent
                    }
                }

            workerWith(
                repository,
                RecordingMicrofrontendPublisher(),
                clock = MutableClock(Instant.parse("2026-11-14T10:00:00Z")),
                handlers = listOf(Channel.BRUKERVARSEL, Channel.ARBEIDSGIVERVARSEL, Channel.LEDERVARSEL).associateWith { handler },
                metrics = metrics,
            ).runOnce()

            handled.shouldContainExactly(ongoing.id, closing.id)
            repository.attemptedDeliveryIds.shouldContainExactly(ongoing.id, closing.id)
            val nextOpen = Instant.parse("2026-11-16T08:00:00Z")
            repository.postponedDeliveries.shouldContainExactly(scheduled.id to nextOpen, employer.id to nextOpen, leader.id to nextOpen)
            metrics.deliveryPostponed.values.sumOf { it.get() } shouldBe 3
        }

        test("a Friday failure waits through the weekend and retries in the next open window") {
            val delivery =
                validMicrofrontendDelivery(UUID.randomUUID()).copy(
                    channel = Channel.BRUKERVARSEL,
                    payload = BrukervarselCreate(TEST_SYKMELDT, Varseltype.OPPGAVE, "Synthetic task"),
                )
            val repository = PollingDeliveryRepository(listOf(delivery))
            val clock = MutableClock(Instant.parse("2026-11-13T12:00:00Z"))
            var calls = 0
            val handler =
                object : ChannelHandler {
                    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
                        if (++calls == 1) error("Temporary failure")
                        return DeliveryOutcome.Sent
                    }
                }
            val worker =
                workerWith(
                    repository,
                    RecordingMicrofrontendPublisher(),
                    clock = clock,
                    handlers = mapOf(Channel.BRUKERVARSEL to handler),
                )

            worker.runOnce()
            clock.current = Instant.parse("2026-11-14T10:00:00Z")
            worker.runOnce()
            calls shouldBe 1
            repository.attemptedDeliveryIds.shouldHaveSize(1)
            repository.sentDeliveryIds.shouldBeEmpty()

            clock.current = Instant.parse("2026-11-16T08:00:00Z")
            worker.runOnce()
            calls shouldBe 2
            repository.attemptedDeliveryIds.shouldHaveSize(2)
            repository.sentDeliveryIds.shouldContainExactly(delivery.id)
        }

        test("the sending window is checked for each delivery when a batch crosses closing time") {
            val first =
                validMicrofrontendDelivery(UUID.randomUUID()).copy(
                    channel = Channel.BRUKERVARSEL,
                    payload = BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "Synthetic message"),
                )
            val second = first.copy(id = UUID.randomUUID())
            val repository = PollingDeliveryRepository(listOf(first, second))
            val clock = MutableClock(Instant.parse("2026-11-13T18:59:59Z"))
            val handled = mutableListOf<UUID>()
            val handler =
                object : ChannelHandler {
                    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
                        handled += delivery.id
                        clock.current = Instant.parse("2026-11-13T19:00:00Z")
                        return DeliveryOutcome.Sent
                    }
                }

            workerWith(
                repository,
                RecordingMicrofrontendPublisher(),
                clock = clock,
                handlers = mapOf(Channel.BRUKERVARSEL to handler),
            ).runOnce()

            handled.shouldContainExactly(first.id)
            repository.postponedDeliveries.shouldContainExactly(second.id to Instant.parse("2026-11-16T08:00:00Z"))
        }

        test("runOnce sends microfrontend deliveries and marks them SENT") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000201")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(validMicrofrontendDelivery(deliveryId)),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val worker = workerWith(repository, publisher, batchSize = 10)

            worker.runOnce()

            repository.lastClaimLimit shouldBe 10
            repository.lastClaimChannels.single() shouldBe Channel.MICROFRONTEND
            publisher.published.shouldHaveSize(1)
            repository.sentDeliveryIds.shouldContainExactly(deliveryId)
            repository.failedDeliveries.shouldBeEmpty()
        }

        test("sent delivery log carries correlation fields") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000210")
            val repository =
                PollingDeliveryRepository(deliveries = listOf(validMicrofrontendDelivery(deliveryId)))
            val publisher = RecordingMicrofrontendPublisher()

            val logbackLogger = LoggerFactory.getLogger(DeliveryWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, publisher).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Delivery sent successfully") }) {
                level shouldBe Level.INFO
                val fields = structuredFields()
                fields[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000301"
                fields[MdcKeys.DELIVERY_ID] shouldBe deliveryId.toString()
                fields[MdcKeys.REFERENCE] shouldBe "ref-1"
                mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000301"
                mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-1"
            }
        }

        test("failed delivery log carries correlation fields") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000216")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(nonMicrofrontendPayload(deliveryId)),
                )
            val publisher = RecordingMicrofrontendPublisher()

            val logbackLogger = LoggerFactory.getLogger(DeliveryWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, publisher).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Marked delivery as FAILED") }) {
                level shouldBe Level.WARN
                val fields = structuredFields()
                fields["event_type"] shouldBe DeliveryLogEvents.markedFailed.name
                fields[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000302"
                fields[MdcKeys.DELIVERY_ID] shouldBe deliveryId.toString()
                fields[MdcKeys.REFERENCE] shouldBe "ref-2"
                fields[MdcKeys.REASON] shouldBe "Payload does not match MICROFRONTEND channel: BrukervarselCreate"
                mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000302"
                mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-2"
            }
        }

        test("row failure log carries channel and handler without stacktrace") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000212")
            val repository =
                PollingDeliveryRepository(deliveries = listOf(validMicrofrontendDelivery(deliveryId)))
            val publisher = RecordingMicrofrontendPublisher()

            val logbackLogger = LoggerFactory.getLogger(LeaseBudgetDrainer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(
                    repository = repository,
                    publisher = publisher,
                    handlers = mapOf(Channel.MICROFRONTEND to ThrowingChannelHandler()),
                ).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Failed processing claimed row") }) {
                level shouldBe Level.WARN
                val fields = structuredFields()
                fields["event_type"] shouldBe LeaseWorkerLogEvents.claimedRowProcessingFailed.name
                fields[MdcKeys.DELIVERY_ID] shouldBe deliveryId.toString()
                fields[MdcKeys.DELIVERY_CHANNEL] shouldBe "MICROFRONTEND"
                fields[MdcKeys.REFERENCE] shouldBe "ref-1"
                fields[MdcKeys.HANDLER] shouldBe "ThrowingChannelHandler"
                fields[MdcKeys.ERROR_TYPE] shouldBe "IllegalStateException"
                throwableProxy shouldBe null
            }
        }

        test("systemic abort log carries channel and handler with useful stacktrace") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000213")
            val repository =
                PollingDeliveryRepository(
                    deliveries =
                        listOf(
                            validMicrofrontendDelivery(deliveryId),
                            validMicrofrontendDelivery(UUID.fromString("00000000-0000-0000-0000-000000000214")),
                            validMicrofrontendDelivery(UUID.fromString("00000000-0000-0000-0000-000000000215")),
                        ),
                )
            val publisher = RecordingMicrofrontendPublisher()

            val logbackLogger = LoggerFactory.getLogger(LeaseBudgetDrainer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                shouldThrow<AlreadyLoggedWorkerFailure> {
                    workerWith(
                        repository = repository,
                        publisher = publisher,
                        handlers = mapOf(Channel.MICROFRONTEND to ThrowingChannelHandler()),
                        maxConsecutiveItemFailures = 3,
                    ).runOnce()
                }
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Aborting batch drain") }) {
                level shouldBe Level.ERROR
                val fields = structuredFields()
                fields["event_type"] shouldBe LeaseWorkerLogEvents.batchDrainAborted.name
                fields[MdcKeys.DELIVERY_CHANNEL] shouldBe "MICROFRONTEND"
                fields[MdcKeys.HANDLER] shouldBe "ThrowingChannelHandler"
                fields[MdcKeys.ERROR_TYPE] shouldBe "IllegalStateException"
                throwableProxy.className shouldBe "java.lang.IllegalStateException"
            }
        }

        test("runOnce marks delivery FAILED when payload does not match the channel") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000202")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(nonMicrofrontendPayload(deliveryId)),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val worker = workerWith(repository, publisher)

            worker.runOnce()

            publisher.published.shouldBeEmpty()
            repository.sentDeliveryIds.shouldBeEmpty()
            repository.failedDeliveries.shouldHaveSize(1)
            repository.failedDeliveries.single().first shouldBe deliveryId
            repository.failedDeliveries
                .single()
                .second
                .shouldNotBeBlank()
        }

        test("runOnce records delivery metrics per channel and result") {
            val sentId = UUID.fromString("00000000-0000-0000-0000-000000000210")
            val failedId = UUID.fromString("00000000-0000-0000-0000-000000000211")
            val repository =
                PollingDeliveryRepository(
                    deliveries =
                        listOf(
                            validMicrofrontendDelivery(sentId),
                            nonMicrofrontendPayload(failedId),
                        ),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val metrics = RecordingDeliveryMetrics()

            workerWith(repository, publisher, metrics = metrics).runOnce()

            metrics.deliveryClaimed.get() shouldBe 2
            metrics.deliverySent[Channel.MICROFRONTEND]?.get() shouldBe 1
            metrics.deliveryFailed[Channel.MICROFRONTEND]?.get() shouldBe 1
            metrics.deliveryEmptyPolls.get() shouldBe 0
        }

        test("runOnce records an empty poll when nothing is claimed") {
            val repository = PollingDeliveryRepository(deliveries = emptyList())
            val publisher = RecordingMicrofrontendPublisher()
            val metrics = RecordingDeliveryMetrics()

            workerWith(repository, publisher, metrics = metrics).runOnce()

            metrics.deliveryEmptyPolls.get() shouldBe 1
            metrics.deliveryClaimed.get() shouldBe 0
        }

        test("lost sent and failed transitions count claim loss and log WARN without counting outcomes") {
            val repository =
                PollingDeliveryRepository(
                    deliveries =
                        listOf(
                            validMicrofrontendDelivery(UUID.randomUUID()),
                            nonMicrofrontendPayload(UUID.randomUUID()),
                        ),
                    transitionSucceeds = false,
                )
            val metrics = RecordingDeliveryMetrics()
            val logbackLogger = LoggerFactory.getLogger(DeliveryWorker::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(repository, RecordingMicrofrontendPublisher(), metrics = metrics).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            metrics.deliveryClaimLost[Channel.MICROFRONTEND]?.get() shouldBe 2
            metrics.deliverySent[Channel.MICROFRONTEND] shouldBe null
            metrics.deliveryFailed[Channel.MICROFRONTEND] shouldBe null
            appender.list.filter { it.formattedMessage.contains("Delivery claim lost") }.map { it.level } shouldBe
                listOf(Level.WARN, Level.WARN)
        }

        test("runOnce stops draining when the lease budget is exhausted") {
            val clock = MutableClock(fromEpochMilliseconds(0))
            val repository =
                PollingDeliveryRepository(
                    deliveries =
                        listOf(
                            validMicrofrontendDelivery(UUID.randomUUID()),
                            validMicrofrontendDelivery(UUID.randomUUID()),
                        ),
                    onClaim = {
                        clock.current += 1.milliseconds
                    },
                )
            val publisher = RecordingMicrofrontendPublisher()
            val worker =
                workerWith(
                    repository = repository,
                    publisher = publisher,
                    leaseDuration = 1.milliseconds,
                    leaseBudgetFraction = 0.1,
                    clock = clock,
                )

            worker.runOnce()

            publisher.published.shouldBeEmpty()
            repository.sentDeliveryIds.shouldBeEmpty()
            repository.failedDeliveries.shouldBeEmpty()
        }

        test("closing the composed loop stops polling") {
            val claimed = CountDownLatch(2)
            val repository =
                PollingDeliveryRepository(
                    deliveries = emptyList(),
                ) {
                    claimed.countDown()
                }
            val publisher = RecordingMicrofrontendPublisher()
            val worker = workerWith(repository, publisher)
            val loop = BackgroundLoop("delivery-worker", 10.milliseconds, iteration = worker::runOnce)

            loop.start()
            claimed.await(5, TimeUnit.SECONDS) shouldBe true
            loop.close()

            val claimCountAfterClose = repository.claimCount.get()
            Thread.sleep(100)
            repository.claimCount.get() shouldBe claimCountAfterClose
        }
    })

private fun workerWith(
    repository: PollingDeliveryRepository,
    publisher: RecordingMicrofrontendPublisher,
    batchSize: Int = 10,
    leaseDuration: Duration = 5.minutes,
    leaseBudgetFraction: Double = 0.8,
    maxConsecutiveItemFailures: Int = LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
    clock: Clock = Clock.System,
    metrics: DeliveryMetrics = NoDeliveryMetrics,
    handlers: Map<Channel, ChannelHandler> = mapOf(Channel.MICROFRONTEND to MicrofrontendChannelHandler(publisher)),
): DeliveryWorker =
    DeliveryWorker(
        repository = repository,
        handlers = handlers,
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
        clock = clock,
    )

private class ThrowingChannelHandler : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome = error("downstream unavailable")
}

private class PollingDeliveryRepository(
    private val deliveries: List<ClaimedDelivery>,
    private val transitionSucceeds: Boolean = true,
    private val onClaim: () -> Unit = {},
) : DeliveryRepository {
    var lastClaimLimit: Int? = null
        private set
    var lastClaimChannels: Set<Channel> = emptySet()
        private set
    val claimCount = AtomicInteger(0)
    val sentDeliveryIds = mutableListOf<UUID>()
    val failedDeliveries = mutableListOf<Pair<UUID, String>>()
    val postponedDeliveries = mutableListOf<Pair<UUID, Instant>>()

    override suspend fun postponeUntil(
        claim: DeliveryClaim,
        nextAttempt: Instant,
    ): Boolean {
        if (!transitionSucceeds) return false
        postponedDeliveries += claim.deliveryId to nextAttempt
        return true
    }

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) = Unit

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): List<ClaimedDelivery> {
        lastClaimLimit = limit
        lastClaimChannels = channels
        claimCount.incrementAndGet()
        onClaim()
        return deliveries
    }

    val attemptedDeliveryIds = mutableListOf<UUID>()

    override suspend fun beginAttempt(
        claim: DeliveryClaim,
        maxAttempts: Int,
    ): Boolean {
        check(deliveries.single { it.id == claim.deliveryId }.claim == claim)
        attemptedDeliveryIds += claim.deliveryId
        return true
    }

    override suspend fun markSent(claim: DeliveryClaim): Boolean {
        check(deliveries.single { it.id == claim.deliveryId }.claim == claim)
        if (!transitionSucceeds) return false
        sentDeliveryIds += claim.deliveryId
        return true
    }

    override suspend fun markFailed(
        claim: DeliveryClaim,
        reason: String,
    ): Boolean {
        check(deliveries.single { it.id == claim.deliveryId }.claim == claim)
        if (!transitionSucceeds) return false
        failedDeliveries += claim.deliveryId to reason
        return true
    }
}

private fun validMicrofrontendDelivery(deliveryId: UUID): ClaimedDelivery =
    ClaimedDelivery(
        id = deliveryId,
        claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000303")),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000301"),
        reference = "ref-1",
        channel = Channel.MICROFRONTEND,
        payload =
            MicrofrontendEnable(
                personIdentifier = PersonIdentifier("12345678901"),
                microfrontendId = "syfo-microfrontend",
            ),
    )

private fun nonMicrofrontendPayload(deliveryId: UUID): ClaimedDelivery =
    ClaimedDelivery(
        id = deliveryId,
        claimToken = ClaimToken(UUID.fromString("00000000-0000-0000-0000-000000000304")),
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000302"),
        reference = "ref-2",
        channel = Channel.MICROFRONTEND,
        payload =
            BrukervarselCreate(
                personIdentifier = PersonIdentifier("12345678901"),
                varseltype = Varseltype.BESKJED,
                text = "Hei",
            ),
    )
