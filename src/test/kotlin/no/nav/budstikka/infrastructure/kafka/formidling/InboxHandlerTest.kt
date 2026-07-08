package no.nav.budstikka.infrastructure.kafka.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.formidling.FormidlingHeader
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingRepository
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
                val repository = FakeInboxFormidlingRepository()
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                handler.handle(validRecord(eventId = "00000000-0000-0000-0000-000000000001"))

                repository.lagredeHendelser.size shouldBe 1
                repository.lagredeHendelser.single().second shouldBe "00000000-0000-0000-0000-000000000001"
                deadRepo.lagredeDeadLetters.size shouldBe 0
            }

            test("duplikat (samme event_id) gir ingen ny rad og kaster ikke") {
                val repository = FakeInboxFormidlingRepository(skalReturnereNyRad = false)
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                handler.handle(validRecord(eventId = "00000000-0000-0000-0000-000000000001"))

                repository.lagredeHendelser.size shouldBe 1
                deadRepo.lagredeDeadLetters.size shouldBe 0
            }
        }

        context("Poison-meldinger (dead-letter)") {
            test("ugyldig JSON behandles som rå payload og lagres") {
                val repository = FakeInboxFormidlingRepository()
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                // invalid JSON but eventId present in header -> should be stored as raw payload
                handler.handle(record(value = "dette er ikke json {{{", eventId = UUID.randomUUID().toString()))

                repository.lagredeHendelser.size shouldBe 1
                deadRepo.lagredeDeadLetters.size shouldBe 0
            }

            test("tom payload dead-letteres og kaster ikke") {
                val repository = FakeInboxFormidlingRepository()
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                handler.handle(record(value = null, eventId = UUID.randomUUID().toString()))

                repository.lagredeHendelser.size shouldBe 0
                deadRepo.lagredeDeadLetters.size shouldBe 1
                deadRepo.lagredeDeadLetters.single().failureReason shouldBe "MISSING_PAYLOAD"
            }

            test("Kafka-koordinater bevares på dead-letter-raden") {
                val repository = FakeInboxFormidlingRepository()
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                handler.handle(record(value = "ugyldig", partition = 2, offset = 42L, key = "partisjon-key"))

                val deadLetter = deadRepo.lagredeDeadLetters.single()
                deadLetter.topic shouldBe TOPIC
                deadLetter.partition shouldBe 2
                deadLetter.kafkaOffset shouldBe 42L
                deadLetter.kafkaKey shouldBe "partisjon-key"
            }
        }

        context("Transient DB-feil") {
            test("DB-feil ved lagreHendelse kaster og dead-letter-tabell berøres ikke") {
                val repository = ThrowingFormidlingRepository()
                val deadRepo = FakeDeadLetterRepository()
                val handler = InboxHandler(repository, deadRepo)

                val result = runCatching { handler.handle(validRecord()) }

                result.isFailure shouldBe true
                deadRepo.lagredeDeadLetters.size shouldBe 0
            }
        }
    })

// --- Fakes ---

private class FakeInboxFormidlingRepository(
    private val skalReturnereNyRad: Boolean = true,
) : InboxFormidlingRepository {
    // Pair: (payload, eventId.toString)
    val lagredeHendelser = mutableListOf<Pair<String, String>>()

    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean {
        lagredeHendelser += payload to eventId.toString()
        return skalReturnereNyRad
    }
}

private class ThrowingFormidlingRepository : InboxFormidlingRepository {
    override suspend fun save(
        eventId: UUID,
        payload: String,
    ): Boolean = error("DB nede — transient feil")
}

// --- Helpers ---

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
            "eventId": "$eventId",
            "referanse": "$reference",
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
