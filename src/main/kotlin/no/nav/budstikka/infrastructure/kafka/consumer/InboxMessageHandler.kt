package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
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
    private val inboxMessageRepository: InboxMessageRepository,
    private val deadLetterRepository: DeadLetterMessageRepository,
) : BatchMessageHandler<String, String?> {
    private val logger = LoggerFactory.getLogger(InboxMessageHandler::class.java)

    override suspend fun handleBatch(records: List<ConsumerRecord<String, String?>>) {
        if (records.isEmpty()) {
            return
        }
        val candidates = records.map { it.toInboxCandidate() }
        val validEvents =
            candidates
                .filterIsInstance<InboxCandidate.Valid>()
                .map(InboxCandidate.Valid::event)
        handleValidEvents(validEvents)
        val deadLetters =
            candidates
                .filterIsInstance<InboxCandidate.DeadLetter>()
                .map(InboxCandidate.DeadLetter::record)
        handleDeadLetters(deadLetters)
    }

    private suspend fun handleDeadLetters(deadLetters: List<DeadLetterRecord>) {
        if (deadLetters.isEmpty()) {
            return
        }
        deadLetterRepository.saveBatch(deadLetters)
        deadLetters.forEach { deadLetter ->
            logger.warn(
                "Poison inbox message dead-lettered failureReason={} topic={} partition={} offset={}",
                deadLetter.failureReason,
                deadLetter.topic,
                deadLetter.partition,
                deadLetter.kafkaOffset,
            )
        }
    }

    private suspend fun handleValidEvents(validEvents: List<InboxMessage>) {
        if (validEvents.isEmpty()) {
            return
        }
        inboxMessageRepository.saveBatch(validEvents.map { it.eventId to it.payload })
        validEvents.forEach { event ->
            logger.info(
                "Inbox message handled eventId={} topic={} partition={} offset={}",
                event.eventId,
                event.topic,
                event.partition,
                event.kafkaOffset,
            )
        }
    }

    /** Mapper poison record til dead-letter-rad med rå payload bevart. */
    private fun ConsumerRecord<String, String?>.toDeadLetter(reason: DeadLetter): DeadLetterRecord =
        DeadLetterRecord(
            payload = value().orEmpty(),
            topic = topic(),
            partition = partition(),
            kafkaOffset = offset(),
            kafkaKey = key(),
            failureReason = reason.code,
            errorMessage = reason.message,
        )

    private fun ConsumerRecord<String, String?>.toInboxCandidate(): InboxCandidate {
        val eventId =
            when (val result = readEventId()) {
                is EventId.Valid -> {
                    result.value
                }

                is EventId.Invalid -> {
                    return InboxCandidate.DeadLetter(
                        toDeadLetter(result.reason),
                    )
                }
            }

        val payload = value()
        if (payload.isNullOrBlank()) {
            return InboxCandidate.DeadLetter(
                toDeadLetter(DeadLetter.MissingPayload),
            )
        }

        return InboxCandidate.Valid(
            InboxMessage(
                eventId = eventId,
                payload = payload,
                topic = topic(),
                partition = partition(),
                kafkaOffset = offset(),
            ),
        )
    }
}

internal sealed interface EventId {
    data class Valid(
        val value: UUID,
    ) : EventId

    data class Invalid(
        val reason: DeadLetter,
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
            ?: return EventId.Invalid(DeadLetter.MissingEventId)
    return try {
        EventId.Valid(UUID.fromString(String(raw, Charsets.UTF_8)))
    } catch (_: IllegalArgumentException) {
        EventId.Invalid(DeadLetter.InvalidEventId)
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
        override val message = "Kafka record missing event ID header"
    }

    data object InvalidEventId : DeadLetter {
        override val code = "INVALID_EVENT_ID"
        override val message = "Kafka event ID header is not a valid UUID"
    }
}

private data class InboxMessage(
    val eventId: UUID,
    val payload: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
)

private sealed interface InboxCandidate {
    data class Valid(
        val event: InboxMessage,
    ) : InboxCandidate

    data class DeadLetter(
        val record: DeadLetterRecord,
    ) : InboxCandidate
}
