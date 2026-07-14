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
 * - `inbox_claimed_total`, `inbox_empty_polls_total`, `inbox_processed_total`,
 *   `inbox_dropped_total{reason}`, `inbox_failed_total`
 * - `delivery_claimed_total`, `delivery_empty_polls_total`, `delivery_total{channel,result}`
 *
 * Labels er lav-kardinale og PII-frie: [Channel]-navn (lowercase) og faste utfall. Tellingen skjer
 * på replicaens beslutning/leveranse — i et lease-kappløp (ADR 0004) kan en taper telle et utfall
 * uten å skrive rad; det er akseptert støy for observerbarhet, ikke en regnskapskilde.
 */
class MicrometerDispatchMetrics(
    private val registry: MeterRegistry,
) : DispatchMetrics {
    private val inboxClaimed = counter("inbox.claimed")
    private val inboxEmptyPolls = counter("inbox.empty.polls")
    private val inboxProcessed = counter("inbox.processed")
    private val inboxFailed = counter("inbox.failed")
    private val deliveryClaimed = counter("delivery.claimed")
    private val deliveryEmptyPolls = counter("delivery.empty.polls")

    override fun inboxClaimed(count: Int) = inboxClaimed.increment(count.toDouble())

    override fun inboxEmptyPoll() = inboxEmptyPolls.increment()

    override fun inboxProcessed() = inboxProcessed.increment()

    override fun inboxDropped(reason: DropReason) =
        Counter
            .builder("inbox.dropped")
            .tag("reason", reason.name.lowercase())
            .register(registry)
            .increment()

    override fun inboxFailed() = inboxFailed.increment()

    override fun deliveryClaimed(count: Int) = deliveryClaimed.increment(count.toDouble())

    override fun deliveryEmptyPoll() = deliveryEmptyPolls.increment()

    override fun deliverySent(channel: Channel) = delivery(channel, result = "sent")

    override fun deliveryFailed(channel: Channel) = delivery(channel, result = "failed")

    private fun delivery(
        channel: Channel,
        result: String,
    ) = Counter
        .builder("delivery")
        .tag("channel", channel.name.lowercase())
        .tag("result", result)
        .register(registry)
        .increment()

    private fun counter(name: String): Counter = Counter.builder(name).register(registry)
}
