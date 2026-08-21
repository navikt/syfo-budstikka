package no.nav.budstikka.application.delivery

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.worker.AlreadyLoggedWorkerFailure
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Tag
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.FakeNarmesteLederLookup
import no.nav.budstikka.fakes.TEST_ORGNUMMER
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
import kotlin.time.Instant.Companion.fromEpochMilliseconds

class DeliveryWorkerTest :
    FunSpec({
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

            val event = appender.list.single { it.formattedMessage.contains("Delivery sent successfully") }
            event.formattedMessage shouldContain "${MdcKeys.EVENT_ID}=00000000-0000-0000-0000-000000000301"
            event.formattedMessage shouldContain "${MdcKeys.DELIVERY_ID}=$deliveryId"
            event.formattedMessage shouldContain "${MdcKeys.REFERENCE}=ref-1"
            event.mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000301"
            event.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-1"
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

            val event = appender.list.single { it.formattedMessage.contains("Marked delivery as FAILED") }
            event.formattedMessage shouldContain "${MdcKeys.EVENT_ID}=00000000-0000-0000-0000-000000000302"
            event.formattedMessage shouldContain "${MdcKeys.DELIVERY_ID}=$deliveryId"
            event.formattedMessage shouldContain "${MdcKeys.REFERENCE}=ref-2"
            event.formattedMessage shouldContain "${MdcKeys.REASON}=Payload does not match MICROFRONTEND channel"
            event.mdcPropertyMap[MdcKeys.EVENT_ID] shouldBe "00000000-0000-0000-0000-000000000302"
            event.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-2"
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

            val event = appender.list.single { it.formattedMessage.contains("Failed processing claimed row") }
            event.formattedMessage shouldContain deliveryId.toString()
            event.formattedMessage shouldContain "MICROFRONTEND"
            event.formattedMessage shouldContain "ref-1"
            event.formattedMessage shouldContain "ThrowingChannelHandler"
            event.formattedMessage shouldContain "IllegalStateException"
            event.throwableProxy shouldBe null
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

            val event = appender.list.single { it.formattedMessage.contains("Aborting batch drain") }
            event.formattedMessage shouldContain "MICROFRONTEND"
            event.formattedMessage shouldContain "ThrowingChannelHandler"
            event.formattedMessage shouldContain "IllegalStateException"
            event.throwableProxy.className shouldContain "IllegalStateException"
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

        test("retryable AG close failures stay on the lease retry path without leaking reference or external id") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000217")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(retryableArbeidsgivervarselCloseDelivery(deliveryId)),
                )
            val publisher = CountingCloseFailurePublisher()
            val handler =
                ArbeidsgivervarselChannelHandler(
                    publisher,
                    FakeNarmesteLederLookup(),
                    NoDeliveryMetrics,
                )

            val logbackLogger = LoggerFactory.getLogger(LeaseBudgetDrainer::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                workerWith(
                    repository = repository,
                    publisher = RecordingMicrofrontendPublisher(),
                    handlers = mapOf(Channel.ARBEIDSGIVERVARSEL to handler),
                ).runOnce()
                workerWith(
                    repository = repository,
                    publisher = RecordingMicrofrontendPublisher(),
                    handlers = mapOf(Channel.ARBEIDSGIVERVARSEL to handler),
                ).runOnce()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            repository.lastClaimChannels.single() shouldBe Channel.ARBEIDSGIVERVARSEL
            publisher.closeAttempts shouldBe 2
            repository.attemptedDeliveryIds shouldContainExactly listOf(deliveryId, deliveryId)
            repository.sentDeliveryIds.shouldBeEmpty()
            repository.failedDeliveries.shouldBeEmpty()
            val failureLogs = appender.list.filter { it.formattedMessage.contains("Failed processing claimed row") }
            failureLogs shouldHaveSize 2
            failureLogs.forEach { event ->
                event.formattedMessage shouldNotContain "close-ref"
                event.formattedMessage shouldNotContain "sensitive-create-external-id"
                event.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe null
            }
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
    )

private class ThrowingChannelHandler : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome = error("downstream unavailable")
}

private class PollingDeliveryRepository(
    private val deliveries: List<ClaimedDelivery>,
    private val onClaim: () -> Unit = {},
) : DeliveryRepository {
    var lastClaimLimit: Int? = null
        private set
    var lastClaimChannels: Set<Channel> = emptySet()
        private set
    val claimCount = AtomicInteger(0)
    val sentDeliveryIds = mutableListOf<UUID>()
    val failedDeliveries = mutableListOf<Pair<UUID, String>>()

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) = Unit

    override fun findCreateForFerdigstillInTransaction(match: FerdigstillMatch) = null

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
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean {
        attemptedDeliveryIds += deliveryId
        return true
    }

    override suspend fun markSent(deliveryId: UUID): Boolean {
        sentDeliveryIds += deliveryId
        return true
    }

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean {
        failedDeliveries += deliveryId to reason
        return true
    }
}

private fun validMicrofrontendDelivery(deliveryId: UUID): ClaimedDelivery =
    ClaimedDelivery(
        id = deliveryId,
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

private fun retryableArbeidsgivervarselCloseDelivery(deliveryId: UUID): ClaimedDelivery =
    ClaimedDelivery(
        id = deliveryId,
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000303"),
        reference = "close-ref",
        channel = Channel.ARBEIDSGIVERVARSEL,
        payload =
            ArbeidsgivervarselCreate(
                orgnummer = TEST_ORGNUMMER,
                recipient = AltinnResource(AltinnResourceId.DIALOGMOETE),
                tag = Tag.DIALOGMOETE,
                text = "Tekst",
                link = "https://nav.no/lenke",
            ),
        operation = Operation.INACTIVATE,
        createExternalId = "sensitive-create-external-id",
    )

private class CountingCloseFailurePublisher : ArbeidsgiverNotificationPublisher {
    var closeAttempts = 0
        private set

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        ArbeidsgiverNotificationResponse.Published

    override suspend fun close(request: ArbeidsgiverNotificationCloseRequest): ArbeidsgiverNotificationResponse {
        closeAttempts += 1
        error("Arbeidsgiver notification API could not confirm close because the notification was not found")
    }
}
