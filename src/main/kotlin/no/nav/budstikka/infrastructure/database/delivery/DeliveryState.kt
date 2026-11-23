package no.nav.budstikka.infrastructure.database.delivery

/**
 * Outbox-state for `delivery` rows. Persists as TEXT values in `delivery.state` via [name], just as
 * inbox does for `inbox_message.state`.
 *
 * - [READY]: selectable when `next_attempt_time` is null or has passed.
 * - [CLAIMED]: picked by a delivery worker and leased until `next_attempt_time`.
 * - [SENT] / [FAILED]: terminal outcomes for the current outbox iteration.
 */
enum class DeliveryState {
    READY,
    CLAIMED,
    SENT,
    FAILED,
}
