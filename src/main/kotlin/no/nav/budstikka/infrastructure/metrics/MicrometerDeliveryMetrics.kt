package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.domain.decision.Channel

/**
 * Micrometer adapter for [DeliveryMetrics]. Counts domain events in the shared registry;
 * names follow the Prometheus convention (Micrometer dot names → `snake_case`, counters gain `_total`):
 *
 * - `delivery_claimed_total`, `delivery_empty_polls_total`, `delivery_total{channel,result}`
 * - `narmeste_leder_missing_total{reason}`
 *
 * Labels are low-cardinality and PII-free: lowercase [Channel] names and fixed outcomes. Counting
 * happens before the final state transition is guaranteed, so these metrics are observability
 * signals rather than an accounting source.
 */
class MicrometerDeliveryMetrics(
    private val registry: MeterRegistry,
) : DeliveryMetrics {
    private val claimedCounter = counter(DELIVERY_CLAIMED)
    private val emptyPollsCounter = counter(DELIVERY_EMPTY_POLLS)

    override fun claimed(count: Int) = claimedCounter.increment(count.toDouble())

    override fun emptyPoll() = emptyPollsCounter.increment()

    override fun sent(channel: Channel) = delivery(channel, result = RESULT_SENT)

    override fun failed(channel: Channel) = delivery(channel, result = RESULT_FAILED)

    override fun narmesteLederMissing(reason: NarmesteLederMissingReason) =
        Counter
            .builder(NARMESTE_LEDER_MISSING)
            .tag(TAG_REASON, reason.name.lowercase())
            .register(registry)
            .increment()

    private fun delivery(
        channel: Channel,
        result: String,
    ) = Counter
        .builder(DELIVERY)
        .tag(TAG_CHANNEL, channel.name.lowercase())
        .tag(TAG_RESULT, result)
        .register(registry)
        .increment()

    private fun counter(name: String): Counter = Counter.builder(name).register(registry)

    /**
     * Meter names (Micrometer dot form) and label keys in one source of truth, so the adapter and
     * tests reference the same string. The Prometheus registry converts dots to `_` and adds `_total`
     * during scraping; [MeterRegistry.get] lookup uses the dot form here.
     */
    companion object {
        const val DELIVERY_CLAIMED = "delivery.claimed"
        const val DELIVERY_EMPTY_POLLS = "delivery.empty.polls"
        const val DELIVERY = "delivery"
        const val NARMESTE_LEDER_MISSING = "narmeste.leder.missing"

        const val TAG_REASON = "reason"
        const val TAG_CHANNEL = "channel"
        const val TAG_RESULT = "result"

        const val RESULT_SENT = "sent"
        const val RESULT_FAILED = "failed"
    }
}
