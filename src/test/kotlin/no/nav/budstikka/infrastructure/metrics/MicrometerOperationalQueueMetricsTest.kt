package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.observability.DeliveryQueueKey
import no.nav.budstikka.application.observability.DeliveryQueueState
import no.nav.budstikka.application.observability.InboxQueueState
import no.nav.budstikka.application.observability.OperationalQueueSnapshot
import no.nav.budstikka.application.observability.QueueStats
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.DELIVERY_QUEUE_OLDEST_AGE
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.DELIVERY_QUEUE_SIZE
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.INBOX_QUEUE_OLDEST_AGE
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.INBOX_QUEUE_SIZE
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.SNAPSHOT_LAST_SUCCESS
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.TAG_CHANNEL
import no.nav.budstikka.infrastructure.metrics.MicrometerOperationalQueueMetrics.Companion.TAG_STATE
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class MicrometerOperationalQueueMetricsTest :
    FunSpec({
        test("publishes bounded queue gauges and clears groups absent from the next snapshot") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val observedAt = Instant.parse("2026-08-30T12:00:00Z")
            val metrics = MicrometerOperationalQueueMetrics(registry)

            metrics.record(
                OperationalQueueSnapshot(
                    observedAt = observedAt,
                    inbox = mapOf(InboxQueueState.DUE to QueueStats(2, observedAt - 10.minutes)),
                    deliveries =
                        mapOf(
                            DeliveryQueueKey(Channel.BRUKERVARSEL, DeliveryQueueState.DUE) to
                                QueueStats(1, observedAt - 3.minutes),
                        ),
                ),
            )

            registry.gauge(INBOX_QUEUE_SIZE, TAG_STATE, "due") shouldBeExactly 2.0
            registry.gauge(INBOX_QUEUE_OLDEST_AGE, TAG_STATE, "due") shouldBeExactly 600.0
            registry.gauge(DELIVERY_QUEUE_SIZE, TAG_CHANNEL, "brukervarsel", TAG_STATE, "due") shouldBeExactly 1.0
            registry.gauge(
                DELIVERY_QUEUE_OLDEST_AGE,
                TAG_CHANNEL,
                "brukervarsel",
                TAG_STATE,
                "due",
            ) shouldBeExactly 180.0
            registry.gauge(SNAPSHOT_LAST_SUCCESS) shouldBeExactly observedAt.epochSeconds.toDouble()
            registry.scrape().also { scrape ->
                scrape shouldContain "inbox_queue_size"
                scrape shouldContain "delivery_queue_size"
                scrape shouldContain "queue_snapshot_last_success_timestamp_seconds"
            }

            metrics.record(
                OperationalQueueSnapshot(
                    observedAt = observedAt + 1.minutes,
                    inbox = emptyMap(),
                    deliveries = emptyMap(),
                ),
            )

            registry.gauge(INBOX_QUEUE_SIZE, TAG_STATE, "due") shouldBeExactly 0.0
            registry.gauge(INBOX_QUEUE_OLDEST_AGE, TAG_STATE, "due") shouldBeExactly 0.0
            registry.gauge(DELIVERY_QUEUE_SIZE, TAG_CHANNEL, "brukervarsel", TAG_STATE, "due") shouldBeExactly 0.0
            registry.gauge(SNAPSHOT_LAST_SUCCESS) shouldBeExactly (observedAt + 1.minutes).epochSeconds.toDouble()
        }

        test("clamps clock-skewed oldest timestamps to zero age") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val observedAt = Instant.parse("2026-08-30T12:00:00Z")
            val metrics = MicrometerOperationalQueueMetrics(registry)

            metrics.record(
                OperationalQueueSnapshot(
                    observedAt = observedAt,
                    inbox = mapOf(InboxQueueState.DUE to QueueStats(1, observedAt + 1.minutes)),
                    deliveries = emptyMap(),
                ),
            )

            registry.gauge(INBOX_QUEUE_OLDEST_AGE, TAG_STATE, "due") shouldBeExactly 0.0
        }
    })

private fun PrometheusMeterRegistry.gauge(
    name: String,
    vararg tags: String,
): Double = get(name).tags(*tags).gauge().value()
