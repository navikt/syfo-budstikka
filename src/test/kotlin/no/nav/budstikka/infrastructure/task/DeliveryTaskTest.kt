package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import no.nav.budstikka.application.DeliveryTask
import no.nav.budstikka.application.MicrofrontendChannelHandler
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.MicrofrontendPublisher
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.Microfrontend
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.Varseltype
import no.nav.budstikka.infrastructure.task.config.LeaseDrainConfig
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeliveryTaskTest :
    FunSpec({
        test("runOnce sends microfrontend deliveries and marks them SENT") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000201")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(validMicrofrontendDelivery(deliveryId)),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val task = taskWith(repository, publisher, batchSize = 10)

            task.runOnce()

            repository.lastClaimLimit shouldBe 10
            repository.lastClaimChannels.single() shouldBe Channel.MICROFRONTEND
            publisher.published.shouldHaveSize(1)
            repository.sentDeliveryIds.shouldContainExactly(deliveryId)
            repository.failedDeliveries.shouldBeEmpty()
        }

        test("runOnce marks delivery FAILED when payload does not match the channel") {
            val deliveryId = UUID.fromString("00000000-0000-0000-0000-000000000202")
            val repository =
                PollingDeliveryRepository(
                    deliveries = listOf(nonMicrofrontendPayload(deliveryId)),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val task = taskWith(repository, publisher)

            task.runOnce()

            publisher.published.shouldBeEmpty()
            repository.sentDeliveryIds.shouldBeEmpty()
            repository.failedDeliveries.shouldHaveSize(1)
            repository.failedDeliveries.single().first shouldBe deliveryId
            repository.failedDeliveries
                .single()
                .second
                .shouldNotBeBlank()
        }

        test("runOnce stops draining when the lease budget is exhausted") {
            val repository =
                PollingDeliveryRepository(
                    deliveries =
                        listOf(
                            validMicrofrontendDelivery(UUID.randomUUID()),
                            validMicrofrontendDelivery(UUID.randomUUID()),
                        ),
                )
            val publisher = RecordingMicrofrontendPublisher()
            val task =
                taskWith(
                    repository = repository,
                    publisher = publisher,
                    leaseDuration = Duration.ofMillis(1),
                    leaseBudgetFraction = 0.1,
                )

            task.runOnce()

            publisher.published.shouldBeEmpty()
            repository.sentDeliveryIds.shouldBeEmpty()
            repository.failedDeliveries.shouldBeEmpty()
        }

        test("close stops polling loop") {
            val claimed = CountDownLatch(2)
            val repository =
                PollingDeliveryRepository(
                    deliveries = emptyList(),
                ) {
                    claimed.countDown()
                }
            val publisher = RecordingMicrofrontendPublisher()
            val task =
                taskWith(
                    repository = repository,
                    publisher = publisher,
                    interval = Duration.ofMillis(10),
                )

            task.start()
            claimed.await(5, TimeUnit.SECONDS) shouldBe true
            task.close()

            val claimCountAfterClose = repository.claimCount.get()
            Thread.sleep(100)
            repository.claimCount.get() shouldBe claimCountAfterClose
        }
    })

private fun taskWith(
    repository: PollingDeliveryRepository,
    publisher: RecordingMicrofrontendPublisher,
    interval: Duration = Duration.ofSeconds(1),
    batchSize: Int = 10,
    leaseDuration: Duration = Duration.ofMinutes(5),
    leaseBudgetFraction: Double = 0.8,
): DeliveryTask =
    DeliveryTask(
        repository = repository,
        handlers = mapOf(Channel.MICROFRONTEND to MicrofrontendChannelHandler(publisher)),
        drainer = LeaseBudgetDrainer(leaseBudgetFraction),
        config =
            LeaseDrainConfig(
                interval = interval,
                batchSize = batchSize,
                leaseDuration = leaseDuration,
                leaseBudgetFraction = leaseBudgetFraction,
            ),
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

private class RecordingMicrofrontendPublisher : MicrofrontendPublisher {
    val published = mutableListOf<Microfrontend>()

    override suspend fun publish(microfrontend: Microfrontend) {
        published += microfrontend
    }
}

private fun validMicrofrontendDelivery(deliveryId: UUID): ClaimedDelivery =
    ClaimedDelivery(
        id = deliveryId,
        inboxEventId = UUID.fromString("00000000-0000-0000-0000-000000000301"),
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
        channel = Channel.MICROFRONTEND,
        payload =
            BrukervarselCreate(
                personIdentifier = PersonIdentifier("12345678901"),
                varseltype = Varseltype.BESKJED,
                text = "Hei",
            ),
    )
