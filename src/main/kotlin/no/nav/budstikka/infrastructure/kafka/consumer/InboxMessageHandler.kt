package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepository
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
 * (B54) og sealed content dekodes først av beslutnings-workeren.
 */
class InboxMessageHandler(
    private val repository: InboxMessageRepository,
    private val deadLetterRepository: DeadLetterMessageRepository,
) : MessageHandler<String, String?> {
    private val logger = LoggerFactory.getLogger(InboxMessageHandler::class.java)

    override suspend fun handle(record: ConsumerRecord<String, String?>) {
        val eventId =
            when (val result = record.readEventId()) {
                is EventId.Valid -> {
                    result.value
                }

                is EventId.Invalid -> {
                    record.deadLetter(DeadLetter.MissingEventId)
                    return
                }
            }

        val payload = record.value()
        if (payload.isNullOrBlank()) {
            record.deadLetter(DeadLetter.MissingPayload)
            return
        }

        saveEvent(record, eventId, payload)
    }

    private suspend fun saveEvent(
        record: ConsumerRecord<String, String?>,
        eventId: UUID,
        payload: String,
    ) {
        // Transient DB-feil kaster herfra → ConsumerRunner tar seg av re-poll med backoff
        val isNewEvent =
            repository.save(
                eventId = eventId,
                payload = payload,
            )
        logger.info(
            "{} {}",
            if (isNewEvent) {
                "Received formidling"
            } else {
                "Duplicate ignored (event_id already known)"
            },
            record.topicInfo,
        )
    }

    /** Dead-letterer recorden til dead-letter-tabellen med den rå (mulig-ugyldige) payloaden bevart. */
    private suspend fun ConsumerRecord<String, String?>.deadLetter(reason: DeadLetter) {
        logger.warn(
            "Poison message dead-lettered failureReason={} {}",
            reason.code,
            topicInfo,
        )
        deadLetterRepository.save(
            DeadLetterRecord(
                payload = value().orEmpty(),
                topic = topic(),
                partition = partition(),
                kafkaOffset = offset(),
                kafkaKey = key(),
                failureReason = reason.code,
                errorMessage = reason.message,
            ),
        )
    }
}

/** Resultat av å lese event_id fra Kafka-headeren: enten en gyldig UUID eller en dead-letter-grunn. */
internal sealed interface EventId {
    data class Valid(
        val value: UUID,
    ) : EventId

    data class Invalid(
        val failureReason: String,
        val errorMessage: String?,
    ) : EventId
}

/**
 * Ren header-lesing (B54, dedup-fast-path) uten å røre bodyen — ingen bivirkninger, så den bor
 * på filnivå og testes isolert. Skiller manglende header fra ugyldig UUID så [InboxMessageHandler] kan
 * dead-lettere med riktig feilårsak.
 */
internal fun ConsumerRecord<*, *>.readEventId(): EventId {
    val raw =
        headers().lastHeader(DispatchHeader.EVENT_ID)?.value()
            ?: return EventId.Invalid("MISSING_EVENT_ID", "Kafka header '${DispatchHeader.EVENT_ID}' missing")
    return try {
        EventId.Valid(UUID.fromString(String(raw, Charsets.UTF_8)))
    } catch (e: IllegalArgumentException) {
        EventId.Invalid("INVALID_EVENT_ID", e.message)
    }
}

sealed interface DeadLetter {
    val code: String
    val message: String

    data object MissingPayload : DeadLetter {
        override val code = "MISSING_PAYLOAD"
        override val message = "Kafka record missing payload"
    }

    data object MissingEventId : DeadLetter {
        override val code = "MISSING_EVENT_ID"
        override val message = "Kafka record missing event ID"
    }
}

private val ConsumerRecord<*, *>.topicInfo
    get() = "topic=${topic()} partition=${partition()} offset=${offset()}"
