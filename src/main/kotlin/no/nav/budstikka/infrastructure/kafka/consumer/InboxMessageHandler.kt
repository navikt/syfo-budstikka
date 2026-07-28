package no.nav.budstikka.infrastructure.kafka.consumer

import kotlinx.serialization.SerializationException
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.dispatchJson
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Parses the full [Dispatch] at ingest and persists a hydrated, deduplicated inbox row (ADR 0008).
 * Deduplicates on `event_id` (PK, ON CONFLICT DO NOTHING) from the Kafka header, deliberately after
 * parsing rather than before (deviation from ADR 0008 point 3; see ADR note).
 *
 * Failure taxonomy: syntactically invalid input (missing/invalid header, empty or unparseable payload)
 * is dead-lettered and its offset committed; transient failure (database unavailable) is rethrown
 * without commit for re-poll.
 *
 * PII: a parse failure can include fnr in its exception message (kotlinx echoes raw JSON), so never
 * log the message or throwable: log only the failure code.
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
                .map(InboxCandidate.Valid::record)
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
        // DL can lack eventId: correlate by Kafka coordinates, not MDC. Never log payload (B58).
        deadLetters.forEach { deadLetter ->
            logger.warn(
                "Poison inbox message dead-lettered {} {} {} {}",
                kv(MdcKeys.REASON, deadLetter.failureReason),
                kv(MdcKeys.TOPIC, deadLetter.topic),
                kv(MdcKeys.PARTITION, deadLetter.partition),
                kv(MdcKeys.KAFKA_OFFSET, deadLetter.kafkaOffset),
            )
        }
    }

    private suspend fun handleValidEvents(validEvents: List<ValidRecord>) {
        if (validEvents.isEmpty()) {
            return
        }
        inboxMessageRepository.saveBatch(validEvents.map(ValidRecord::message))
        // eventId in MDC so the Loki line for consumption correlates with the rest of the flow (B45).
        validEvents.forEach { record ->
            MDC.putCloseable(MdcKeys.EVENT_ID, record.message.eventId.toString()).use {
                logger.info(
                    "Inbox message handled {} {} {}",
                    kv(MdcKeys.TOPIC, record.topic),
                    kv(MdcKeys.PARTITION, record.partition),
                    kv(MdcKeys.KAFKA_OFFSET, record.kafkaOffset),
                )
            }
        }
    }

    private fun ConsumerRecord<String, String?>.toDeadLetter(
        reason: DeadLetter,
        eventId: UUID?,
    ): DeadLetterRecord =
        DeadLetterRecord(
            payload = value().orEmpty(),
            topic = topic(),
            partition = partition(),
            kafkaOffset = offset(),
            kafkaKey = key(),
            eventId = eventId,
            failureReason = reason.code,
            errorMessage = reason.message,
        )

    private fun ConsumerRecord<String, String?>.toInboxCandidate(): InboxCandidate {
        val eventId =
            when (val result = readEventId()) {
                is EventId.Valid -> result.value
                is EventId.Invalid -> return InboxCandidate.DeadLetter(toDeadLetter(result.reason, eventId = null))
            }

        val payload = value()
        if (payload.isNullOrBlank()) {
            return InboxCandidate.DeadLetter(toDeadLetter(DeadLetter.MissingPayload, eventId = eventId))
        }

        val dispatch =
            when (val parsed = parseDispatch(payload)) {
                is ParseResult.Success -> {
                    parsed.dispatch
                }

                is ParseResult.Failure -> {
                    return InboxCandidate.DeadLetter(toDeadLetter(DeadLetter.UnparseablePayload, eventId = eventId))
                }
            }

        return InboxCandidate.Valid(
            ValidRecord(
                message = InboxMessage(eventId = eventId, reference = dispatch.reference, content = dispatch.content),
                topic = topic(),
                partition = partition(),
                kafkaOffset = offset(),
            ),
        )
    }
}

internal fun parseDispatch(payload: String): ParseResult =
    try {
        ParseResult.Success(dispatchJson.decodeFromString<Dispatch>(payload))
    } catch (_: SerializationException) {
        ParseResult.Failure
    } catch (_: IllegalArgumentException) {
        ParseResult.Failure
    }

internal sealed interface ParseResult {
    data class Success(
        val dispatch: Dispatch,
    ) : ParseResult

    data object Failure : ParseResult
}

internal sealed interface EventId {
    data class Valid(
        val value: UUID,
    ) : EventId

    data class Invalid(
        val reason: DeadLetter,
    ) : EventId
}

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

    data object UnparseablePayload : DeadLetter {
        override val code = "UNPARSEABLE_PAYLOAD"
        override val message = "Kafka record payload could not be parsed as a Dispatch"
    }
}

private data class ValidRecord(
    val message: InboxMessage,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
)

private sealed interface InboxCandidate {
    data class Valid(
        val record: ValidRecord,
    ) : InboxCandidate

    data class DeadLetter(
        val record: DeadLetterRecord,
    ) : InboxCandidate
}
