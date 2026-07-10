package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import no.nav.budstikka.application.EffectuateDecision
import no.nav.budstikka.application.InboxMessageTask
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.fakes.FakeTransactionRunner
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepository
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessage
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
import no.nav.budstikka.infrastructure.task.config.InboxMessageTaskConfig
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InboxMessageTaskTest :
    FunSpec({
        test("runOnce marks valid payloads processed and invalid payloads failed") {
            val validEventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val invalidEventId = UUID.fromString("00000000-0000-0000-0000-000000000002")
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            InboxMessage(eventId = validEventId, payload = validPayload(validEventId)),
                            InboxMessage(eventId = invalidEventId, payload = "{not valid json"),
                        ),
                )
            val task = taskWith(repository, batchSize = 10)

            task.runOnce()

            repository.lastPollLimit shouldBe 10
            repository.processedEventIds.shouldContainExactly(validEventId)
            repository.failedMessages.shouldHaveSize(1)
            repository.failedMessages.single().first shouldBe invalidEventId
            repository.failedMessages
                .single()
                .second
                .shouldNotBeBlank()
        }

        test("close stops polling loop") {
            val polled = CountDownLatch(2)
            val repository =
                PollingInboxMessageRepository(
                    messages = emptyList(),
                ) {
                    polled.countDown()
                }
            val task =
                taskWith(
                    repository,
                    interval = Duration.ofMillis(10),
                    batchSize = InboxMessageTaskConfig.DEFAULT_BATCH_SIZE,
                )

            task.start()
            polled.await(5, TimeUnit.SECONDS) shouldBe true
            task.close()

            val pollCountAfterClose = repository.pollCount.get()
            Thread.sleep(100)
            repository.pollCount.get() shouldBe pollCountAfterClose
        }
    })

private fun taskWith(
    repository: PollingInboxMessageRepository,
    interval: Duration = Duration.ofSeconds(1),
    batchSize: Int = 10,
): InboxMessageTask =
    InboxMessageTask(
        repository = repository,
        effectuator =
            EffectuateDecision(
                transactionRunner = FakeTransactionRunner(),
                inboxMessageRepository = repository,
                deliveryRepository = RecordingDeliveryRepository(),
            ),
        config = InboxMessageTaskConfig(interval = interval, batchSize = batchSize),
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

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = true

    override suspend fun pollReceived(limit: Int): List<InboxMessage> {
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
}

private fun validPayload(eventId: UUID): String =
    """
    {
      "eventId":"$eventId",
      "reference":"ref-1",
      "content":{
        "type":"MicrofrontendEnable",
        "personIdentifier":"12345678901",
        "mikrofrontendId":"syfo-mikrofrontend"
      }
    }
    """.trimIndent()
