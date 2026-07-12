package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.dispatch.DispatchHeader
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional
import java.util.UUID

private const val TOPIC = "team-esyfo.formidling.v1"

class ReadEventIdTest :
    FunSpec({
        test("valid UUID header returns Valid with parsed value") {
            val id = UUID.randomUUID()

            record(eventId = id.toString())
                .readEventId()
                .shouldBeInstanceOf<EventId.Valid>()
                .value shouldBe id
        }

        test("missing header returns Invalid(MISSING_EVENT_ID)") {
            record(eventId = null)
                .readEventId()
                .shouldBeInstanceOf<EventId.Invalid>()
                .reason shouldBe DeadLetter.MissingEventId
        }

        test("header that is not a UUID returns Invalid(INVALID_EVENT_ID)") {
            record(eventId = "ikke-en-uuid")
                .readEventId()
                .shouldBeInstanceOf<EventId.Invalid>()
                .reason shouldBe DeadLetter.InvalidEventId
        }
    })

private fun record(eventId: String?): ConsumerRecord<String, String?> {
    val headers = RecordHeaders()
    if (eventId != null) {
        headers.add(DispatchHeader.EVENT_ID, eventId.toByteArray(Charsets.UTF_8))
    }
    return ConsumerRecord(TOPIC, 0, 0L, 0L, TimestampType.NO_TIMESTAMP_TYPE, -1, -1, "key", "{}", headers, Optional.empty())
}
