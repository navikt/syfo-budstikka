package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.port.DispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

/**
 * Micrometer-adapteren for [DispatchMetrics] (ADR 0007). Teller domenehendelser på det delte
 * registeret; navnene følger Prometheus-konvensjon (Micrometer punkt-navn → `snake_case`, tellere
 * får `_total`):
 *
 * - `inbox_message_claimed_total`, `inbox_message_empty_polls_total`,
 *   `inbox_message_processed_total`, `inbox_message_dropped_total{reason}`,
 *   `inbox_message_failed_total`
 * - `delivery_claimed_total`, `delivery_empty_polls_total`, `delivery_total{channel,result}`
 *
 * Labels er lav-kardinale og PII-frie: [Channel]-navn (lowercase) og faste utfall. Tellingen skjer
 * på replicaens beslutning/leveranse — i et lease-kappløp (ADR 0004) kan en taper telle et utfall
 * uten å skrive rad; det er akseptert støy for observerbarhet, ikke en regnskapskilde.
 */
class MicrometerDispatchMetrics(
    private val registry: MeterRegistry,
) : DispatchMetrics {
    private val inboxClaimed = counter(INBOX_MESSAGE_CLAIMED)
    private val inboxEmptyPolls = counter(INBOX_MESSAGE_EMPTY_POLLS)
    private val inboxProcessed = counter(INBOX_MESSAGE_PROCESSED)
    private val inboxFailed = counter(INBOX_MESSAGE_FAILED)
    private val deliveryClaimed = counter(DELIVERY_CLAIMED)
    private val deliveryEmptyPolls = counter(DELIVERY_EMPTY_POLLS)

    override fun inboxClaimed(count: Int) = inboxClaimed.increment(count.toDouble())

    override fun inboxEmptyPoll() = inboxEmptyPolls.increment()

    override fun inboxProcessed() = inboxProcessed.increment()

    override fun inboxDropped(reason: DropReason) =
        Counter
            .builder(INBOX_MESSAGE_DROPPED)
            .tag(TAG_REASON, reason.name.lowercase())
            .register(registry)
            .increment()

    override fun inboxFailed() = inboxFailed.increment()

    override fun deliveryClaimed(count: Int) = deliveryClaimed.increment(count.toDouble())

    override fun deliveryEmptyPoll() = deliveryEmptyPolls.increment()

    override fun deliverySent(channel: Channel) = delivery(channel, result = RESULT_SENT)

    override fun deliveryFailed(channel: Channel) = delivery(channel, result = RESULT_FAILED)

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
     * Meter-navn (Micrometer punkt-form) og label-nøkler som én sanngjeningskilde, slik at både
     * adapteren og testene refererer samme streng. Prometheus-registeret oversetter punkt → `_` og
     * legger på `_total` i selve scrapet; oppslag via [MeterRegistry.get] bruker punkt-formen her.
     */
    companion object {
        const val INBOX_MESSAGE_CLAIMED = "inbox.message.claimed"
        const val INBOX_MESSAGE_EMPTY_POLLS = "inbox.message.empty.polls"
        const val INBOX_MESSAGE_PROCESSED = "inbox.message.processed"
        const val INBOX_MESSAGE_DROPPED = "inbox.message.dropped"
        const val INBOX_MESSAGE_FAILED = "inbox.message.failed"
        const val DELIVERY_CLAIMED = "delivery.claimed"
        const val DELIVERY_EMPTY_POLLS = "delivery.empty.polls"
        const val DELIVERY = "delivery"

        const val TAG_REASON = "reason"
        const val TAG_CHANNEL = "channel"
        const val TAG_RESULT = "result"

        const val RESULT_SENT = "sent"
        const val RESULT_FAILED = "failed"
    }
}
