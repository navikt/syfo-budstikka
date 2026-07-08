package no.nav.budstikka.infrastructure.kafka.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.FormidlingHeader
import no.nav.budstikka.infrastructure.database.inbox.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.inbox.InboxRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import java.util.Optional
import java.util.UUID

private const val TOPIC = "team-esyfo.formidling.v1"

class InboxHandlerTest :
    FunSpec({
        context("Gyldig formidling") {
            test("gyldig melding lagres i inbox og returnerer uten feil") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(gyldigRecord(eventId = "00000000-0000-0000-0000-000000000001"))

                repository.lagredeHendelser.size shouldBe 1
                repository.lagredeHendelser.single().second shouldBe "00000000-0000-0000-0000-000000000001"
                repository.lagredeDeadLetters.size shouldBe 0
            }

            test("duplikat (samme event_id) gir ingen ny rad og kaster ikke") {
                val repository = FakeInboxRepository(skalReturnereNyRad = false)
                val handler = InboxHandler(repository)

                handler.handle(gyldigRecord(eventId = "00000000-0000-0000-0000-000000000001"))

                repository.lagredeHendelser.size shouldBe 1
                repository.lagredeDeadLetters.size shouldBe 0
            }
        }

        context("Poison-meldinger (dead-letter)") {
            test("ugyldig JSON behandles som rå payload og lagres") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                // invalid JSON but eventId present in header -> should be stored as raw payload
                handler.handle(record(value = "dette er ikke json {{{", eventId = UUID.randomUUID().toString()))

                repository.lagredeHendelser.size shouldBe 1
                repository.lagredeDeadLetters.size shouldBe 0
            }

            test("null-payload dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(record(value = null))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "NULL_PAYLOAD"
            }

            test("manglende event_id-header dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                // payload med gyldig JSON, men ingen eventId-header
                val payload = """{"referanse":"ref-1","innhold":{"type":"BrukervarselOpprett"}}"""
                handler.handle(record(value = payload))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "MANGLER_EVENT_ID"
            }

            test("ugyldig event_id-header dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(record(value = "{}", eventId = "ikke-en-uuid"))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "UGYLDIG_EVENT_ID"
            }

            test("Kafka-koordinater bevares på dead-letter-raden") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(record(value = "ugyldig", partition = 2, offset = 42L, key = "partisjon-key"))

                val deadLetter = repository.lagredeDeadLetters.single()
                deadLetter.topic shouldBe TOPIC
                deadLetter.partisjon shouldBe 2
                deadLetter.kafkaOffset shouldBe 42L
                deadLetter.kafkaKey shouldBe "partisjon-key"
            }
        }

        context("Transient DB-feil") {
            test("DB-feil ved lagreHendelse kaster og dead-letter-tabell berøres ikke") {
                val repository = ThrowingRepository()
                val handler = InboxHandler(repository)

                val result = runCatching { handler.handle(gyldigRecord()) }

                result.isFailure shouldBe true
                repository.lagredeDeadLetters.size shouldBe 0
            }
        }
    })

// --- Fakes ---

private class FakeInboxRepository(
    private val skalReturnereNyRad: Boolean = true,
) : InboxRepository {
    // Pair: (payload, eventId.toString)
    val lagredeHendelser = mutableListOf<Pair<String, String>>()
    val lagredeDeadLetters = mutableListOf<DeadLetterRecord>()

    override suspend fun lagreHendelse(
        eventId: UUID,
        payload: String,
    ): Boolean {
        lagredeHendelser += payload to eventId.toString()
        return skalReturnereNyRad
    }

    override suspend fun lagreFeilet(record: DeadLetterRecord) {
        lagredeDeadLetters += record
    }
}

private class ThrowingRepository : InboxRepository {
    val lagredeDeadLetters = mutableListOf<DeadLetterRecord>()

    override suspend fun lagreHendelse(
        eventId: UUID,
        payload: String,
    ): Boolean = error("DB nede — transient feil")

    override suspend fun lagreFeilet(record: DeadLetterRecord) {
        lagredeDeadLetters += record
    }
}

// --- Helpers ---

private fun gyldigRecord(
    eventId: String = UUID.randomUUID().toString(),
    referanse: String = "ref-1",
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
): ConsumerRecord<String, String?> {
    val payload =
        """
        {
            "eventId": "$eventId",
            "referanse": "$referanse",
            "innhold": {
                "type": "BrukervarselOpprett",
                "personident": "12345678901",
                "varseltype": "BESKJED",
                "tekst": "Hei"
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
