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
 * - **Poison** (mangler/ugyldig event_id-header, eller tom payload): dead-letter til inbox_feilet,
 *   returner normalt → offset committes, partisjon flyter videre.
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
        val eventId =
            when (val resultat = record.lesEventId()) {
                is EventId.Ok -> resultat.verdi
                is EventId.Ugyldig -> {
                    record.deadLetter(resultat.feilaarsak, resultat.feilmelding)
                    return
                }
            }

        val payload = record.value()
        if (payload.isNullOrBlank()) {
            record.deadLetter("TOM_PAYLOAD", "Kafka-record manglet payload")
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

    /** Dead-letterer recorden til inbox_feilet med den rå (mulig-ugyldige) payloaden bevart. */
    private suspend fun ConsumerRecord<String, String?>.deadLetter(
        feilaarsak: String,
        feilmelding: String?,
    ) {
        logger.warn(
            "Poison-melding dead-letteret feilaarsak={} {}",
            feilaarsak,
            topicInfo,
        )
        repository.lagreFeilet(
            DeadLetterRecord(
                payload = value().orEmpty(),
                topic = topic(),
                partisjon = partition(),
                kafkaOffset = offset(),
                kafkaKey = key(),
                feilaarsak = feilaarsak,
                feilmelding = feilmelding,
            ),
        )
    }
}

/** Resultat av å lese event_id fra Kafka-headeren: enten en gyldig UUID eller en dead-letter-grunn. */
internal sealed interface EventId {
    data class Ok(
        val verdi: UUID,
    ) : EventId

    data class Ugyldig(
        val feilaarsak: String,
        val feilmelding: String?,
    ) : EventId
}

/**
 * Ren header-lesing (B54, dedup-fast-path) uten å røre bodyen — ingen bivirkninger, så den bor
 * på filnivå og testes isolert. Skiller manglende header fra ugyldig UUID så [InboxHandler] kan
 * dead-lettere med riktig feilårsak.
 */
internal fun ConsumerRecord<*, *>.lesEventId(): EventId {
    val raw =
        headers().lastHeader(FormidlingHeader.EVENT_ID)?.value()
            ?: return EventId.Ugyldig("MANGLER_EVENT_ID", "Kafka-header '${FormidlingHeader.EVENT_ID}' mangler")
    return try {
        EventId.Ok(UUID.fromString(String(raw, Charsets.UTF_8)))
    } catch (e: IllegalArgumentException) {
        EventId.Ugyldig("UGYLDIG_EVENT_ID", e.message)
    }
}

private val ConsumerRecord<*, *>.topicInfo
    get() = "topic=${topic()} partition=${partition()} offset=${offset()}"
