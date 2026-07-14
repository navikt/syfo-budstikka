package no.nav.budstikka.infrastructure.kafka.consumer

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID

class ReadEventIdTest :
    FunSpec({
        test("valid UUID header returns Valid with parsed value") {
            val id = UUID.randomUUID()

            testRecord(value = "{}", eventId = id.toString())
                .readEventId()
                .shouldBeInstanceOf<EventId.Valid>()
                .value shouldBe id
        }

        test("missing header returns Invalid(MISSING_EVENT_ID)") {
            testRecord(value = "{}", eventId = null)
                .readEventId()
                .shouldBeInstanceOf<EventId.Invalid>()
                .reason shouldBe DeadLetter.MissingEventId
        }

        test("header that is not a UUID returns Invalid(INVALID_EVENT_ID)") {
            testRecord(value = "{}", eventId = "ikke-en-uuid")
                .readEventId()
                .shouldBeInstanceOf<EventId.Invalid>()
                .reason shouldBe DeadLetter.InvalidEventId
        }
    })
