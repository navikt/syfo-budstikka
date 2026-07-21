package no.nav.budstikka.infrastructure.kafka.consumer

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import java.util.UUID

class InboxHandlerTest :
    FunSpec({
        test("valid dispatch is parsed, hydrated and saved in inbox") {
            with(createTestContext()) {
                val eventId = "00000000-0000-0000-0000-000000000001"
                handler.handle(validRecord(eventId = eventId, reference = "ref-1"))

                inboxRepository.savedEvents.size shouldBe 1
                with(inboxRepository.savedEvents.single()) {
                    this.eventId shouldBe UUID.fromString(eventId)
                    reference shouldBe "ref-1"
                    content.partitionKey shouldBe "12345678901"
                }
                deadLetterRepository.savedDeadLetters.size shouldBe 0
            }
        }

        test("duplicate dispatch (same event_id) yields no new row and does not throw") {
            with(createTestContext()) {
                handler.handle(validRecord(eventId = "00000000-0000-0000-0000-000000000001"))

                inboxRepository.savedEvents.size shouldBe 1
                deadLetterRepository.savedDeadLetters.size shouldBe 0
            }
        }

        test("unparseable payload (corrupt JSON) is dead-lettered with the header eventId preserved") {
            with(createTestContext()) {
                val eventId = UUID.randomUUID()
                handler.handle(testRecord(value = "dette er ikke json {{{", eventId = eventId.toString()))

                inboxRepository.savedEvents.size shouldBe 0
                with(deadLetterRepository.savedDeadLetters.single()) {
                    failureReason shouldBe "UNPARSEABLE_PAYLOAD"
                    this.eventId shouldBe eventId
                }
            }
        }

        test("unknown content subtype is dead-lettered as UNPARSEABLE_PAYLOAD") {
            with(createTestContext()) {
                val payload = """{"reference":"ref-1","content":{"type":"UkjentCreate"}}"""
                handler.handle(testRecord(value = payload, eventId = UUID.randomUUID().toString()))

                inboxRepository.savedEvents.size shouldBe 0
                deadLetterRepository.savedDeadLetters.single().failureReason shouldBe "UNPARSEABLE_PAYLOAD"
            }
        }

        test("envelope without reference is dead-lettered as UNPARSEABLE_PAYLOAD") {
            with(createTestContext()) {
                val payload =
                    """{"content":{"type":"BrukervarselCreate","personIdentifier":"12345678901","varseltype":"BESKJED","text":"Hei"}}"""
                handler.handle(testRecord(value = payload, eventId = UUID.randomUUID().toString()))

                inboxRepository.savedEvents.size shouldBe 0
                deadLetterRepository.savedDeadLetters.single().failureReason shouldBe "UNPARSEABLE_PAYLOAD"
            }
        }

        test("a parse failure never leaks payload content (fnr) to the log (B46/B58 PII)") {
            with(createTestContext()) {
                val fnr = "31129956715"
                // Structurally broken JSON whose decode error echoes the raw input (incl. the fnr).
                val leakyPayload = """{"reference":"r","content":{"personIdentifier":"$fnr" """

                val logbackLogger = LoggerFactory.getLogger(InboxMessageHandler::class.java) as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                logbackLogger.addAppender(appender)
                try {
                    handler.handle(testRecord(value = leakyPayload, eventId = UUID.randomUUID().toString()))
                } finally {
                    logbackLogger.detachAppender(appender)
                    appender.stop()
                }

                deadLetterRepository.savedDeadLetters.single().failureReason shouldBe "UNPARSEABLE_PAYLOAD"
                // The reason message is a static constant, never the exception message.
                deadLetterRepository.savedDeadLetters
                    .single()
                    .errorMessage
                    .orEmpty() shouldNotContain fnr
                appender.list.forEach { event ->
                    event.formattedMessage shouldNotContain fnr
                    (event.throwableProxy?.message ?: "") shouldNotContain fnr
                }
            }
        }

        test("empty payload is dead-lettered with the header eventId preserved") {
            with(createTestContext()) {
                val eventId = UUID.randomUUID()
                handler.handle(testRecord(value = null, eventId = eventId.toString()))

                inboxRepository.savedEvents.size shouldBe 0
                deadLetterRepository.savedDeadLetters.size shouldBe 1
                with(deadLetterRepository.savedDeadLetters.single()) {
                    failureReason shouldBe "MISSING_PAYLOAD"
                    this.eventId shouldBe eventId
                }
            }
        }

        test("missing event_id header is dead-lettered without an eventId") {
            with(createTestContext()) {
                handler.handle(testRecord(value = "mangler-header"))

                inboxRepository.savedEvents.size shouldBe 0
                with(deadLetterRepository.savedDeadLetters.single()) {
                    failureReason shouldBe "MISSING_EVENT_ID"
                    eventId shouldBe null
                }
            }
        }

        test("Kafka coordinates are preserved on dead-letter row") {
            with(createTestContext()) {
                handler.handle(testRecord(value = "ugyldig", partition = 2, offset = 42L, key = "partisjon-key"))

                with(deadLetterRepository.savedDeadLetters.single()) {
                    topic shouldBe TOPIC
                    partition shouldBe 2
                    kafkaOffset shouldBe 42L
                    kafkaKey shouldBe "partisjon-key"
                }
            }
        }

        test("poison records are persisted in one dead-letter batch per poll round") {
            with(createTestContext()) {
                val validEventId = "00000000-0000-0000-0000-000000000111"

                handler.handleBatch(
                    listOf(
                        testRecord(value = "mangler-event-id"),
                        testRecord(value = null, eventId = "00000000-0000-0000-0000-000000000222"),
                        validRecord(eventId = validEventId),
                    ),
                )

                inboxRepository.savedEvents.size shouldBe 1
                inboxRepository.savedEvents.single().eventId shouldBe UUID.fromString(validEventId)
                deadLetterRepository.savedDeadLetters.size shouldBe 2
                deadLetterRepository.saveBatchCalls shouldBe 1
            }
        }

        test("transient DB error during saveEvent throws and dead-letter table is not touched") {
            val throwingRepository = ThrowingMessageRepository()
            val deadLetterRepository = FakeDeadLetterRepository()
            val handler = InboxMessageHandler(throwingRepository, deadLetterRepository)

            val result = runCatching { handler.handle(validRecord()) }

            result.isFailure shouldBe true
            deadLetterRepository.savedDeadLetters.size shouldBe 0
        }
    })

private fun createTestContext(): TestContext {
    val inboxRepository = FakeInboxMessageRepository()
    val deadLetterRepository = FakeDeadLetterRepository()
    val handler = InboxMessageHandler(inboxRepository, deadLetterRepository)

    return TestContext(
        handler = handler,
        inboxRepository = inboxRepository,
        deadLetterRepository = deadLetterRepository,
    )
}

private data class TestContext(
    val handler: InboxMessageHandler,
    val inboxRepository: FakeInboxMessageRepository,
    val deadLetterRepository: FakeDeadLetterRepository,
)

private suspend fun InboxMessageHandler.handle(record: ConsumerRecord<String, String?>) {
    handleBatch(listOf(record))
}

private fun validRecord(
    eventId: String = UUID.randomUUID().toString(),
    reference: String = "ref-1",
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
): ConsumerRecord<String, String?> {
    val payload =
        """
        {
            "reference": "$reference",
            "content": {
                "type": "BrukervarselCreate",
                "personIdentifier": "12345678901",
                "varseltype": "BESKJED",
                "text": "Hei"
            }
        }
        """.trimIndent()
    return testRecord(value = payload, partition = partition, offset = offset, key = key, eventId = eventId)
}
