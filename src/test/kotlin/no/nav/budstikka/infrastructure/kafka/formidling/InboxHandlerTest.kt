package no.nav.budstikka.infrastructure.kafka.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.inbox.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.inbox.InboxRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
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
                repository.lagredeHendelser.single().third shouldBe "ref-1"
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
            test("ugyldig JSON dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(record(value = "dette er ikke json {{{"))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "UGYLDIG_JSON"
            }

            test("null-payload dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                handler.handle(record(value = null))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "NULL_PAYLOAD"
            }

            test("manglende event_id dead-letteres og kaster ikke") {
                val repository = FakeInboxRepository()
                val handler = InboxHandler(repository)

                val payloadUtenEventId = """{"referanse":"ref-1","innhold":{"type":"BrukervarselOpprett"}}"""
                handler.handle(record(value = payloadUtenEventId))

                repository.lagredeHendelser.size shouldBe 0
                repository.lagredeDeadLetters.size shouldBe 1
                repository.lagredeDeadLetters.single().feilaarsak shouldBe "UGYLDIG_JSON"
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
    // Triple: (payload, eventId.toString, referanse)
    val lagredeHendelser = mutableListOf<Triple<String, String, String>>()
    val lagredeDeadLetters = mutableListOf<DeadLetterRecord>()

    override suspend fun lagreHendelse(
        eventId: UUID,
        referanse: String,
        payload: String,
    ): Boolean {
        lagredeHendelser += Triple(payload, eventId.toString(), referanse)
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
        referanse: String,
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
    return record(value = payload, partition = partition, offset = offset, key = key)
}

private fun record(
    value: String?,
    partition: Int = 0,
    offset: Long = 0L,
    key: String = "key",
): ConsumerRecord<String, String?> = ConsumerRecord(TOPIC, partition, offset, key, value)
