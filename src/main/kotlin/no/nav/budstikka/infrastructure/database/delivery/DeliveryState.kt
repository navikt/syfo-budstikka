package no.nav.budstikka.infrastructure.database.delivery

/**
 * Outbox-state for `delivery` rows. Persists as TEXT values in `delivery.state` via [name], just as
 * inbox does for `inbox_message.state`.
 *
 * - [READY]: selectable for delivery.
 * - [CLAIMED]: picked by a delivery worker and leased until `next_attempt_time`.
 * - [SENT] / [FAILED]: terminal outcomes for the current outbox iteration.
 */
enum class DeliveryState {
    READY,
    CLAIMED,
    SENT,
    FAILED,
}
