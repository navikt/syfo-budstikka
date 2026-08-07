package no.nav.budstikka.contract

import java.util.UUID

/**
 * Unique, technical id for one dispatch. It is dedup and correlation mechanics, never domain data,
 * and is carried only as the Kafka header [DispatchHeader.EVENT_ID], never in the payload.
 *
 * The Produsent owns the lifecycle: create one id with [new], persist it together with the work that
 * caused the dispatch BEFORE the first send, and reuse the SAME id on every retry. Budstikka then
 * deduplicates a redelivery instead of notifying the person twice. Encoding therefore never generates
 * an id on your behalf; a fresh id on retry would defeat deduplication.
 */
@JvmInline
value class EventId(
    val value: UUID,
) {
    /** Safe to log: a random technical id, with no person data or free text in it. */
    override fun toString(): String = value.toString()

    companion object {
        fun new(): EventId = EventId(UUID.randomUUID())
    }
}
