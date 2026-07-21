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
 * Konsumerer nøytrale dispatch-meldinger fra topic, PARSER hele [Dispatch] ved inntak (ADR 0008)
 * og persisterer idempotent en hydrert rad i inbox_message (event_id fra header + reference +
 * content). Parsingen gjør bare syntaktisk kontraktvalidering + hydrering; ingen forretningslogikk.
 *
 * Feiltaksonomi (jf. docs/datamodell.md):
 * - **Poison** (syntaktisk): manglende/ugyldig event_id-header, tom payload, eller payload som ikke
 *   lar seg parse som en [Dispatch] (korrupt JSON, ukjent sealed-subtype, manglende `reference`).
 *   Dead-letteres → offset committes, partisjon flyter videre. event_id lagres best-effort når
 *   headeren var gyldig.
 * - **Transient** (DB nede): kast → ConsumerRunner committer ikke, re-poller med backoff.
 *
 * Dedup: event_id er PK (ON CONFLICT DO NOTHING) — Kafka-replay dobbeltsender ikke. event_id leses
 * fra Kafka-header ([DispatchHeader.EVENT_ID]) FØR payloaden parses, så dedup for gyldige meldinger er
 * skjema-uavhengig. BEVISST AVVIK fra ADR 0008 pkt. 3 (se ADR-ens implementeringsnotat): vi bygger
 * ikke «forkast kjent duplikat uten parsing» — payloaden parses før PK-dedupen. En korrupt duplikat av
 * en allerede-ingestert eventId dead-letteres derfor (kan gi gjentatte DL-rader). Akseptert edge
 * (produsent-bug; replay er byte-identisk og rammes ikke), for å slippe et ekstra DB-kall i ingest.
 *
 * PII (B46/B58): en parse-feil kan bære payload-innhold (fnr) i exception-meldingen (kotlinx
 * echo-er «JSON input: …»). Vi logger derfor ALDRI exception-meldingen/throwable-en — kun
 * feilårsak-koden. Rå payload bevares i dead_letter_message (egen retensjon, ADR 0008), aldri i logg.
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
        // Dead-letters kan MANGLE en gyldig eventId (MISSING/INVALID_EVENT_ID) → korrelér på
        // Kafka-koordinater, ikke på MDC-eventId (B45). Strukturerte felt via kv (B46). Aldri
        // payload/exception-melding i logg (B58/PII).
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
        inboxMessageRepository.saveBatch(validEvents.map(ValidRecord::message))
        // B45: re-attach eventId på MDC ved konsum-steget så `| event_id="X"` i Loki dekker HELE
        // hendelsesløpet (konsum → decide → poller → send), ikke bare de senere stegene. Øvrige
        // felt struktureres via kv (B46); ingen payload/PII logges.
        validEvents.forEach { record ->
            MDC.putCloseable(MdcKeys.EVENT_ID, record.message.eventId.toString()).use {
                logger.info(
                    "Inbox message handled {} {} {}",
                    kv("topic", record.topic),
                    kv("partition", record.partition),
                    kv("kafkaOffset", record.kafkaOffset),
                )
            }
        }
    }

    /** Mapper poison record til dead-letter-rad med rå payload bevart + best-effort eventId. */
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
 * Parser payloaden som en [Dispatch] uten å røre PII: en feil rapporteres som [ParseResult.Failure]
 * uten å bære exception-meldingen videre (kotlinx echo-er rå input, inkl. fnr — B58). Ren funksjon,
 * så den bor på filnivå og testes isolert.
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

/**
 * Ren header-lesing (dedup-fast-path) uten å røre bodyen — ingen bivirkninger, så den bor på
 * filnivå og testes isolert. Skiller manglende header fra ugyldig UUID så [InboxMessageHandler] kan
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
