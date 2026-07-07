package no.nav.budstikka.infrastructure.kafka.formidling

import kotlinx.serialization.json.Json
import no.nav.budstikka.domain.formidling.FormidlingEnvelope
import no.nav.budstikka.infrastructure.database.inbox.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.inbox.InboxRepository
import no.nav.budstikka.infrastructure.kafka.config.MessageHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory

/**
 * Konsumerer nøytrale formidlinger fra topic og persisterer dem idempotent i inbox_hendelse.
 *
 * Feiltaksonomi (jf. docs/DATAMODELL.md):
 * - **Poison** (ugyldig JSON / mangler event_id): dead-letter til inbox_feilet, returner normalt
 *   → offset committes, partisjon flyter videre.
 * - **Transient** (DB nede): kast → ConsumerRunner committer ikke, re-poller med backoff.
 *
 * Dedup: event_id er PK (ON CONFLICT DO NOTHING) — Kafka-replay dobbeltsender ikke.
 * Payload lagres byte-eksakt som text; sealed innhold dekodes først av beslutnings-workeren.
 */
class InboxHandler(
    private val repository: InboxRepository,
) : MessageHandler<String, String?> {
    private val logger = LoggerFactory.getLogger(InboxHandler::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun handle(record: ConsumerRecord<String, String?>) {
        val payload =
            record.value() ?: run {
                deadLetter(
                    record = record,
                    payload = "",
                    feilaarsak = "NULL_PAYLOAD",
                    feilmelding = "Kafka-record hadde null-verdi",
                )
                return
            }

        val envelope = parseEnvelope(record, payload) ?: return
        saveHendelse(record, envelope, payload)
    }

    private suspend fun saveHendelse(
        record: ConsumerRecord<String, String?>,
        envelope: FormidlingEnvelope,
        payload: String,
    ) {
        // Transient DB-feil kaster herfra → ConsumerRunner tar seg av re-poll med backoff
        val isNewHendelse =
            repository.lagreHendelse(
                eventId = envelope.eventId,
                referanse = envelope.referanse,
                payload = payload,
            )
        logger.info(
            "{} {}",
            if (isNewHendelse) {
                "Mottok formidling"
            } else {
                "Duplikat ignorert (event_id allerede kjent)"
            },
            record.topicInfo,
        )
    }

    private suspend fun parseEnvelope(
        record: ConsumerRecord<String, String?>,
        payload: String,
    ): FormidlingEnvelope? =
        try {
            json.decodeFromString<FormidlingEnvelope>(payload)
        } catch (e: IllegalArgumentException) {
            // Dekodefeil og ugyldig UUID er deterministisk poison ved konsument-grensen.
            deadLetter(
                record = record,
                payload = payload,
                feilaarsak = "UGYLDIG_JSON",
                feilmelding = e.message,
            )
            null
        }

    private suspend fun deadLetter(
        record: ConsumerRecord<String, String?>,
        payload: String,
        feilaarsak: String,
        feilmelding: String?,
    ) {
        logger.warn(
            "Poison-melding dead-letteret feilaarsak={} {}",
            feilaarsak,
            record.topicInfo,
        )
        repository.lagreFeilet(
            DeadLetterRecord(
                payload = payload,
                topic = record.topic(),
                partisjon = record.partition(),
                kafkaOffset = record.offset(),
                kafkaKey = record.key(),
                feilaarsak = feilaarsak,
                feilmelding = feilmelding,
            ),
        )
    }
}

private val ConsumerRecord<*, *>.topicInfo
    get() = "topic=${topic()} partition=${partition()} offset=${offset()}"
