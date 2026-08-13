package no.nav.budstikka.application.delivery

import no.nav.budstikka.domain.decision.Channel

/**
 * Counting-only metrics port: implementations must not throw or perform I/O. Labels are fixed,
 * low-cardinality, and PII-free.
 */
interface DeliveryMetrics {
    fun claimed(count: Int)

    fun emptyPoll()

    fun sent(channel: Channel)

    fun failed(channel: Channel)

    fun narmesteLederMissing(reason: NarmesteLederMissingReason)
}

object NoDeliveryMetrics : DeliveryMetrics {
    override fun claimed(count: Int) = Unit

    override fun emptyPoll() = Unit

    override fun sent(channel: Channel) = Unit

    override fun failed(channel: Channel) = Unit

    override fun narmesteLederMissing(reason: NarmesteLederMissingReason) = Unit
}

/**
 * Closed reasons for the Nærmeste leder lookup metric and its identifier-free delivery failure.
 * Kept with [DeliveryMetrics] so the metric label remains fixed and low-cardinality.
 */
enum class NarmesteLederMissingReason {
    MISSING_ACTIVE_LEADER,
    MISSING_EMAIL_ADDRESS,
}
