package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.observability.DeliveryQueueKey
import no.nav.budstikka.application.observability.DeliveryQueueState
import no.nav.budstikka.application.observability.InboxQueueState
import no.nav.budstikka.application.observability.OperationalQueueMetrics
import no.nav.budstikka.application.observability.OperationalQueueSnapshot
import no.nav.budstikka.application.observability.QueueStats
import no.nav.budstikka.domain.decision.Channel
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration

/**
 * Cached database-snapshot gauges: scraping never performs database I/O. Every pod exports the same
 * shared queue view. PromQL must first filter each queue series by the freshness series from the
 * same pod (`and on(pod) (time() - queue_snapshot_last_success_timestamp_seconds < 90)`), then use
 * `max`/`max by`; never `sum` or two independent maxima. For DUE, oldest age starts when work became
 * claimable; other states use the original received/created timestamp.
 */
class MicrometerOperationalQueueMetrics(
    registry: MeterRegistry,
) : OperationalQueueMetrics {
    private val inbox =
        InboxQueueState.entries.associateWith { state ->
            QueueGauges(
                size = registry.atomicGauge(INBOX_QUEUE_SIZE, TAG_STATE to state.metricValue()),
                oldestAge = registry.atomicGauge(INBOX_QUEUE_OLDEST_AGE, TAG_STATE to state.metricValue()),
            )
        }
    private val deliveries =
        Channel.entries
            .flatMap { channel -> DeliveryQueueState.entries.map { state -> DeliveryQueueKey(channel, state) } }
            .associateWith { key ->
                QueueGauges(
                    size =
                        registry.atomicGauge(
                            DELIVERY_QUEUE_SIZE,
                            TAG_CHANNEL to key.channel.name.lowercase(),
                            TAG_STATE to key.state.metricValue(),
                        ),
                    oldestAge =
                        registry.atomicGauge(
                            DELIVERY_QUEUE_OLDEST_AGE,
                            TAG_CHANNEL to key.channel.name.lowercase(),
                            TAG_STATE to key.state.metricValue(),
                        ),
                )
            }
    private val lastSuccess = registry.atomicGauge(SNAPSHOT_LAST_SUCCESS)

    override fun record(snapshot: OperationalQueueSnapshot) {
        inbox.forEach { (state, gauges) -> gauges.record(snapshot.inbox[state] ?: QueueStats.EMPTY, snapshot) }
        deliveries.forEach { (key, gauges) -> gauges.record(snapshot.deliveries[key] ?: QueueStats.EMPTY, snapshot) }
        lastSuccess.set(snapshot.observedAt.epochSeconds)
    }

    private data class QueueGauges(
        val size: AtomicLong,
        val oldestAge: AtomicLong,
    ) {
        fun record(
            stats: QueueStats,
            snapshot: OperationalQueueSnapshot,
        ) {
            size.set(stats.size)
            oldestAge.set(stats.oldestAt?.let { (snapshot.observedAt - it).nonNegativeSeconds() } ?: 0)
        }
    }

    companion object {
        const val INBOX_QUEUE_SIZE = "inbox.queue.size"
        const val INBOX_QUEUE_OLDEST_AGE = "inbox.queue.oldest.age.seconds"
        const val DELIVERY_QUEUE_SIZE = "delivery.queue.size"
        const val DELIVERY_QUEUE_OLDEST_AGE = "delivery.queue.oldest.age.seconds"
        const val SNAPSHOT_LAST_SUCCESS = "queue.snapshot.last.success.timestamp.seconds"

        const val TAG_CHANNEL = "channel"
        const val TAG_STATE = "state"
    }
}

private fun MeterRegistry.atomicGauge(
    name: String,
    vararg tags: Pair<String, String>,
): AtomicLong =
    AtomicLong().also { value ->
        val builder = Gauge.builder(name, value) { it.get().toDouble() }
        tags.forEach { (key, tagValue) -> builder.tag(key, tagValue) }
        builder.register(this)
    }

private fun InboxQueueState.metricValue(): String = name.lowercase()

private fun DeliveryQueueState.metricValue(): String = name.lowercase()

private fun Duration.nonNegativeSeconds(): Long = if (isNegative()) 0 else inWholeSeconds
