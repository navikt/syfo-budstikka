package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason

class MicrometerDispatchMetricsTest :
    FunSpec({
        test("emits Prometheus-named counters with low-cardinality labels") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerDispatchMetrics(registry)

            metrics.inboxClaimed(3)
            metrics.inboxEmptyPoll()
            metrics.inboxProcessed()
            metrics.inboxDropped(DropReason.DEAD)
            metrics.inboxFailed()
            metrics.deliveryClaimed(2)
            metrics.deliveryEmptyPoll()
            metrics.deliverySent(Channel.MICROFRONTEND)
            metrics.deliveryFailed(Channel.BREV)

            val scrape = registry.scrape()

            scrape shouldContain "inbox_message_claimed_total 3.0"
            scrape shouldContain "inbox_message_empty_polls_total 1.0"
            scrape shouldContain "inbox_message_processed_total 1.0"
            scrape shouldContain "inbox_message_dropped_total{reason=\"dead\"} 1.0"
            scrape shouldContain "inbox_message_failed_total 1.0"
            scrape shouldContain "delivery_claimed_total 2.0"
            scrape shouldContain "delivery_empty_polls_total 1.0"
            scrape shouldContain "delivery_total{channel=\"microfrontend\",result=\"sent\"} 1.0"
            scrape shouldContain "delivery_total{channel=\"brev\",result=\"failed\"} 1.0"
        }
    })
