package no.nav.budstikka.infrastructure.database.dispatch

/**
 * Lifecycle states for an `inbox_message` row. Persisted as TEXT in the `state` column through
 * [name] (the column remains TEXT, not an enum column, so the Flyway schema-drift check stays empty).
 *
 * - [RECEIVED]: initial state, set by Kafka consumption.
 * - [CLAIMED]: claimed by the decision worker with a lease (ADR 0004), invisible to other pollers
 *   until the lease expires or the row is effectuated.
 * - [PROCESSED] / [DROPPED] / [FAILED]: terminal effectuation outcomes (#56).
 *
 * The delivery table has a similar but separate state series (READY/…/SENT). They share a shape, not
 * values, so states remain separate enums per table.
 */
enum class InboxMessageState {
    RECEIVED,
    CLAIMED,
    PROCESSED,
    DROPPED,
    FAILED,
}
