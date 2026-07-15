package no.nav.budstikka.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.application.port.NoDispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.fakes.RecordingDispatchMetrics
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

        test("sent delivery log carries reference on MDC for cross-event (OPPRETT->FERDIGSTILL) correlation") {
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
            event.mdcPropertyMap[MdcKeys.REFERENCE] shouldBe "ref-1"
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
            val metrics = RecordingDispatchMetrics()

            workerWith(repository, publisher, metrics = metrics).runOnce()

            metrics.deliveryClaimed.get() shouldBe 2
            metrics.deliverySent[Channel.MICROFRONTEND]?.get() shouldBe 1
            metrics.deliveryFailed[Channel.MICROFRONTEND]?.get() shouldBe 1
            metrics.deliveryEmptyPolls.get() shouldBe 0
        }

        test("runOnce records an empty poll when nothing is claimed") {
            val repository = PollingDeliveryRepository(deliveries = emptyList())
            val publisher = RecordingMicrofrontendPublisher()
            val metrics = RecordingDispatchMetrics()

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
    metrics: DispatchMetrics = NoDispatchMetrics,
): DeliveryWorker =
    DeliveryWorker(
        repository = repository,
        handlers = mapOf(Channel.MICROFRONTEND to MicrofrontendChannelHandler(publisher)),
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
                mikrofrontendId = "syfo-mikrofrontend",
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
