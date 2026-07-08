package no.nav.budstikka.infrastructure.kafka.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import no.nav.budstikka.domain.formidling.FormidlingHeader
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional
import java.util.UUID

private const val TOPIC = "team-esyfo.formidling.v1"

class ReadEventIdTest :
    FunSpec({
        test("gyldig UUID-header gir Ok med parset verdi") {
            val id = UUID.randomUUID()

            val resultat = record(eventId = id.toString()).readEventId()

            resultat.shouldBeInstanceOf<EventId.Ok>().verdi shouldBe id
        }

        test("manglende header gir Ugyldig(MANGLER_EVENT_ID)") {
            val resultat = record(eventId = null).readEventId()

            resultat.shouldBeInstanceOf<EventId.Ugyldig>().feilaarsak shouldBe "MANGLER_EVENT_ID"
        }

        test("header som ikke er en UUID gir Ugyldig(UGYLDIG_EVENT_ID)") {
            val resultat = record(eventId = "ikke-en-uuid").readEventId()

            resultat.shouldBeInstanceOf<EventId.Ugyldig>().feilaarsak shouldBe "UGYLDIG_EVENT_ID"
        }
    })

private fun record(eventId: String?): ConsumerRecord<String, String?> {
    val headers = RecordHeaders()
    if (eventId != null) {
        headers.add(FormidlingHeader.EVENT_ID, eventId.toByteArray(Charsets.UTF_8))
    }
    return ConsumerRecord(TOPIC, 0, 0L, 0L, TimestampType.NO_TIMESTAMP_TYPE, -1, -1, "key", "{}", headers, Optional.empty())
}
