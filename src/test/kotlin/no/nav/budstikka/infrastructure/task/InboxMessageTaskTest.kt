package no.nav.budstikka.infrastructure.task

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.InboxMessageTask
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
        test("runOnce decodes valid inbox payloads and forwards dispatches") {
            val validEventId = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val repository =
                PollingInboxMessageRepository(
                    messages =
                        listOf(
                            InboxMessage(
                                eventId = validEventId,
                                payload = validPayload(validEventId),
                            ),
                            InboxMessage(
                                eventId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                payload = "{not valid json",
                            ),
                        ),
                )
            val decodedEventIds = mutableListOf<UUID>()
            val task =
                InboxMessageTask(
                    repository = repository,
                    config = InboxMessageTaskConfig(interval = Duration.ofSeconds(1), batchSize = 10),
                ) { dispatch ->
                    decodedEventIds += dispatch.eventId
                }

            task.runOnce()

            repository.lastPollLimit shouldBe 10
            decodedEventIds.shouldContainExactly(validEventId)
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
                InboxMessageTask(
                    repository = repository,
                    config =
                        InboxMessageTaskConfig(
                            interval = Duration.ofMillis(10),
                            batchSize = InboxMessageTaskConfig.DEFAULT_BATCH_SIZE,
                        ),
                )

            task.start()
            polled.await(5, TimeUnit.SECONDS) shouldBe true
            task.close()

            val pollCountAfterClose = repository.pollCount.get()
            Thread.sleep(100)
            repository.pollCount.get() shouldBe pollCountAfterClose
        }
    })

private class PollingInboxMessageRepository(
    private val messages: List<InboxMessage>,
    private val onPoll: () -> Unit = {},
) : InboxMessageRepository {
    var lastPollLimit: Int? = null
        private set
    val pollCount = AtomicInteger(0)

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
}

private fun validPayload(eventId: UUID): String =
    """
    {
      "eventId":"$eventId",
      "referanse":"ref-1",
      "content":{
        "type":"BrukervarselCreate",
        "personIdentifier":"12345678901",
        "varseltype":"BESKJED",
        "text":"Hei"
      }
    }
    """.trimIndent()
