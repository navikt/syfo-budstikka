package no.nav.budstikka.infrastructure.kafka.consumer

import kotlinx.serialization.SerializationException
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.inbox.DeadLetterReason
import no.nav.budstikka.application.inbox.InboxMetrics
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchHeader
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepository
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.util.UUID

/**
 * Parses [Dispatch] at ingest and persists a hydrated inbox row deduplicated by the event ID header.
 *
 * Invalid input is dead-lettered; transient persistence failures are rethrown for re-poll. Parsing
 * failures may echo the payload in exception messages, so this handler never logs them or their
 * throwables.
 */
class InboxMessageHandler(
    private val inboxMessageRepository: InboxMessageRepository,
    private val deadLetterRepository: DeadLetterMessageRepository,
    private val metrics: InboxMetrics,
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
        handleDeadLetters(deadLetters)
    }

    private suspend fun handleDeadLetters(deadLetters: List<InboxCandidate.DeadLetter>) {
        if (deadLetters.isEmpty()) {
            return
        }
        deadLetterRepository.saveBatch(deadLetters.map(InboxCandidate.DeadLetter::record))
        deadLetters
            .groupingBy(InboxCandidate.DeadLetter::reason)
            .eachCount()
            .forEach(metrics::deadLetterPersisted)
        // A dead letter can lack eventId; correlate by Kafka coordinates and never log its payload.
        deadLetters.forEach { deadLetter ->
            val record = deadLetter.record
            logger.warn(
                "Poison inbox message dead-lettered {} {} {} {}",
                kv(MdcKeys.REASON, record.failureReason),
                kv(MdcKeys.TOPIC, record.topic),
                kv(MdcKeys.PARTITION, record.partition),
                kv(MdcKeys.KAFKA_OFFSET, record.kafkaOffset),
            )
        }
    }

    private suspend fun handleValidEvents(validEvents: List<ValidRecord>) {
        if (validEvents.isEmpty()) {
            return
        }
        inboxMessageRepository.saveBatch(validEvents.map(ValidRecord::message))
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
        reason: DeadLetterReason,
        eventId: UUID?,
    ): InboxCandidate.DeadLetter =
        InboxCandidate.DeadLetter(
            reason = reason,
            record =
                DeadLetterRecord(
                    payload = value().orEmpty(),
                    topic = topic(),
                    partition = partition(),
                    kafkaOffset = offset(),
                    kafkaKey = key(),
                    eventId = eventId,
                    failureReason = reason.code,
                    errorMessage = reason.safeMessage,
                ),
        )

    private fun ConsumerRecord<String, String?>.toInboxCandidate(): InboxCandidate {
        val eventId =
            when (val result = readEventId()) {
                is ParsedEventId.Valid -> result.value
                is ParsedEventId.Invalid -> return toDeadLetter(result.reason, eventId = null)
            }

        val payload = value()
        if (payload.isNullOrBlank()) {
            return toDeadLetter(DeadLetterReason.MISSING_PAYLOAD, eventId = eventId)
        }

        val dispatch =
            when (val parsed = parseDispatch(payload)) {
                is ParseResult.Success -> {
                    parsed.dispatch
                }

                is ParseResult.Failure -> {
                    return toDeadLetter(DeadLetterReason.UNPARSEABLE_PAYLOAD, eventId = eventId)
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

/**
 * The outcome of reading the eventId header off a record. Deliberately not named `EventId`: that name
 * belongs to the public contract type [no.nav.budstikka.contract.EventId], and a same-package
 * technical twin would shadow it for every reader of this package. This one is a parse result and
 * carries a raw [UUID], never the contract type.
 */
internal sealed interface ParsedEventId {
    data class Valid(
        val value: UUID,
    ) : ParsedEventId

    data class Invalid(
        val reason: DeadLetterReason,
    ) : ParsedEventId
}

internal fun ConsumerRecord<*, *>.readEventId(): ParsedEventId {
    val raw =
        headers().lastHeader(DispatchHeader.EVENT_ID)?.value()
            ?: return ParsedEventId.Invalid(DeadLetterReason.MISSING_EVENT_ID)
    return try {
        ParsedEventId.Valid(UUID.fromString(String(raw, Charsets.UTF_8)))
    } catch (_: IllegalArgumentException) {
        ParsedEventId.Invalid(DeadLetterReason.INVALID_EVENT_ID)
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
        val reason: DeadLetterReason,
        val record: DeadLetterRecord,
    ) : InboxCandidate
}
