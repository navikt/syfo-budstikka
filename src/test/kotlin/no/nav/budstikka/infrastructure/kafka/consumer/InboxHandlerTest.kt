package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.FormidlingHeader
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional
import java.util.UUID

private const val TOPIC = "team-esyfo.formidling.v1"

class InboxHandlerTest :
    FunSpec({
        test("formidling saved in inbox and returns without error") {
            val (handler, inboxRepository, deadLetterRepository) = createTestContext()

            handler.handle(validRecord(eventId = "00000000-0000-0000-0000-000000000001"))

            inboxRepository.savedEvents.size shouldBe 1
            inboxRepository.savedEvents.single().second shouldBe "00000000-0000-0000-0000-000000000001"
            deadLetterRepository.savedDeadLetters.size shouldBe 0
        }

        test("formidling duplicate (same event_id) yields no new row and does not throw") {
            val (handler, inboxRepository, deadLetterRepository) =
                createTestContext(
                    shouldReturnNewRowCreated = false,
                )

            handler.handle(validRecord(eventId = "00000000-0000-0000-0000-000000000001"))

            inboxRepository.savedEvents.size shouldBe 1
            deadLetterRepository.savedDeadLetters.size shouldBe 0
        }

        test("invalid JSON is treated as raw payload and stored") {
            val (handler, inboxRepository, deadLetterRepository) = createTestContext()

            // invalid JSON but eventId present in header -> should be stored as raw payload
            handler.handle(record(value = "dette er ikke json {{{", eventId = UUID.randomUUID().toString()))

            inboxRepository.savedEvents.size shouldBe 1
            deadLetterRepository.savedDeadLetters.size shouldBe 0
        }

        test("empty payload is dead-lettered and does not throw") {
            val (handler, inboxRepository, deadLetterRepository) = createTestContext()

            handler.handle(record(value = null, eventId = UUID.randomUUID().toString()))

            inboxRepository.savedEvents.size shouldBe 0
            deadLetterRepository.savedDeadLetters.size shouldBe 1
            deadLetterRepository.savedDeadLetters.single().failureReason shouldBe "MISSING_PAYLOAD"
        }

        test("Kafka coordinates are preserved on dead-letter row") {
            val (handler, _, deadLetterRepository) = createTestContext()

            handler.handle(record(value = "ugyldig", partition = 2, offset = 42L, key = "partisjon-key"))

            with(deadLetterRepository.savedDeadLetters.single()) {
                topic shouldBe TOPIC
                partition shouldBe 2
                kafkaOffset shouldBe 42L
                kafkaKey shouldBe "partisjon-key"
            }
        }

        test("transient DB error during saveEvent throws and dead-letter table is not touched") {
            val throwingRepository = ThrowingFormidlingRepository()
            val deadLetterRepository = FakeDeadLetterRepository()
            val handler = InboxHandler(throwingRepository, deadLetterRepository)

            val result = runCatching { handler.handle(validRecord()) }

            result.isFailure shouldBe true
            deadLetterRepository.savedDeadLetters.size shouldBe 0
        }
    })

private fun createTestContext(shouldReturnNewRowCreated: Boolean = true): TestContext {
    val inboxRepository =
        FakeInboxFormidlingRepository(
            shouldReturnNewRowCreated = shouldReturnNewRowCreated,
        )
    val deadLetterRepository = FakeDeadLetterRepository()
    val handler = InboxHandler(inboxRepository, deadLetterRepository)

    return TestContext(
        handler = handler,
        inboxRepository = inboxRepository,
        deadLetterRepository = deadLetterRepository,
    )
}

private data class TestContext(
    val handler: InboxHandler,
    val inboxRepository: FakeInboxFormidlingRepository,
    val deadLetterRepository: FakeDeadLetterRepository,
)

private fun validRecord(
    eventId: String = UUID.randomUUID().toString(),
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
): ConsumerRecord<String, String?> {
    val payload =
        """
        {
            "eventId": "$eventId",
            "content": {
                "type": "BrukervarselCreate",
                "personident": "12345678901",
                "varseltype": "BESKJED",
                "text": "Hei"
            }
        }
        """.trimIndent()
    return record(value = payload, partition = partition, offset = offset, key = key, eventId = eventId)
}

private fun record(
    value: String?,
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
    eventId: String? = null,
): ConsumerRecord<String, String?> =
    if (eventId != null) {
        val headers = RecordHeaders()
        headers.add(FormidlingHeader.EVENT_ID, eventId.toByteArray(Charsets.UTF_8))
        ConsumerRecord(TOPIC, partition, offset, offset, TimestampType.NO_TIMESTAMP_TYPE, -1, -1, key, value, headers, Optional.empty())
    } else {
        ConsumerRecord(TOPIC, partition, offset, key, value)
    }
