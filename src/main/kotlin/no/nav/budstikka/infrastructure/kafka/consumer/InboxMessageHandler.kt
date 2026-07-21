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
 * Parser hele [Dispatch] ved inntak og persisterer en hydrert, deduplisert inbox-rad (ADR 0008).
 * Dedup skjer på event_id (PK, ON CONFLICT DO NOTHING) fra Kafka-headeren — bevisst etter parse, ikke
 * før (avvik fra ADR 0008 pkt. 3, se ADR-notat).
 *
 * Feiltaksonomi: syntaktisk ugyldig (manglende/ugyldig header, tom eller uparsebar payload)
 * dead-letteres og offset committes; transient feil (DB nede) kastes videre uten commit for re-poll.
 *
 * PII: en parse-feil kan bære fnr i exception-meldingen (kotlinx echo-er rå JSON), så vi logger
 * aldri meldingen/throwable-en — kun feilkoden.
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
        // Dead letters can miss a valid eventId, so we log Kafka coordinates for correlation.
        deadLetters.forEach { deadLetter ->
            logger.warn(
                "Poison inbox message dead-lettered {} {} {} {}",
                kv("failureReason", deadLetter.failureReason),
                kv("topic", deadLetter.topic),
                kv("partition", deadLetter.partition),
                kv("kafkaOffset", deadLetter.kafkaOffset),
            )
        }
    }

    private suspend fun handleValidEvents(validEvents: List<ValidRecord>) {
        if (validEvents.isEmpty()) {
            return
        }
        inboxMessageRepository.saveBatch(validEvents.map { it.eventId to it.payload })
        // Re-attach eventId on MDC so correlation works through the full processing flow.
        validEvents.forEach { event ->
            MDC.putCloseable(MdcKeys.EVENT_ID, event.eventId.toString()).use {
                logger.info(
                    "Inbox message handled {} {} {}",
                    kv("topic", record.topic),
                    kv("partition", record.partition),
                    kv("kafkaOffset", record.kafkaOffset),
                )
            }
        }
    }

    private fun ConsumerRecord<String, String?>.toDeadLetter(reason: DeadLetter): DeadLetterRecord =
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
                is ParseResult.Success -> parsed.dispatch
                is ParseResult.Failure ->
                    return InboxCandidate.DeadLetter(toDeadLetter(DeadLetter.UnparseablePayload, eventId = eventId))
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

/**
 * Parser payloaden som [Dispatch]. Bærer aldri exception-meldingen videre — den kan inneholde fnr (B58).
 */
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

/** Leser event_id fra Kafka-headeren; skiller manglende fra ugyldig UUID for riktig dead-letter-årsak. */
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
