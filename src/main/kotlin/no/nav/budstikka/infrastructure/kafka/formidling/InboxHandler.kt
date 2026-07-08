package no.nav.budstikka.infrastructure.kafka.formidling

import no.nav.budstikka.domain.formidling.FormidlingHeader
import no.nav.budstikka.infrastructure.database.inbox.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.inbox.InboxRepository
import no.nav.budstikka.infrastructure.kafka.config.MessageHandler
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Konsumerer nøytrale formidlinger fra topic og persisterer dem idempotent i inbox_hendelse.
 *
 * Feiltaksonomi (jf. docs/DATAMODELL.md):
 * - **Poison** (mangler/ugyldig event_id-header): dead-letter til inbox_feilet, returner normalt
 *   → offset committes, partisjon flyter videre.
 * - **Transient** (DB nede): kast → ConsumerRunner committer ikke, re-poller med backoff.
 *
 * Dedup: event_id er PK (ON CONFLICT DO NOTHING) — Kafka-replay dobbeltsender ikke.
 * Payload lagres byte-eksakt som text uten deserialisering; event_id leses fra Kafka-header
 * (B54) og sealed innhold dekodes først av beslutnings-workeren.
 */
class InboxHandler(
    private val repository: InboxRepository,
) : MessageHandler<String, String?> {
    private val logger = LoggerFactory.getLogger(InboxHandler::class.java)

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

        // eventId leses fra Kafka-header (B54, dedup-fast-path). Payload lagres byte-eksakt uten
        // deserialisering; referanse og sealed innhold dekodes først av beslutnings-workeren.
        val eventIdStr =
            record
                .headers()
                .lastHeader(FormidlingHeader.EVENT_ID)
                ?.value()
                ?.let { String(it, Charsets.UTF_8) }
                ?: run {
                    deadLetter(
                        record = record,
                        payload = payload,
                        feilaarsak = "MANGLER_EVENT_ID",
                        feilmelding = "Kafka-header '${FormidlingHeader.EVENT_ID}' mangler",
                    )
                    return
                }

        val eventId =
            try {
                UUID.fromString(eventIdStr)
            } catch (e: IllegalArgumentException) {
                deadLetter(
                    record = record,
                    payload = payload,
                    feilaarsak = "UGYLDIG_EVENT_ID",
                    feilmelding = e.message,
                )
                return
            }

        saveHendelse(record, eventId, payload)
    }

    private suspend fun saveHendelse(
        record: ConsumerRecord<String, String?>,
        eventId: UUID,
        payload: String,
    ) {
        // Transient DB-feil kaster herfra → ConsumerRunner tar seg av re-poll med backoff
        val isNewHendelse =
            repository.lagreHendelse(
                eventId = eventId,
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
