package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.DELIVERY
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.DELIVERY_CLAIMED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.DELIVERY_EMPTY_POLLS
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.INBOX_MESSAGE_CLAIMED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.INBOX_MESSAGE_DROPPED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.INBOX_MESSAGE_EMPTY_POLLS
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.INBOX_MESSAGE_FAILED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.INBOX_MESSAGE_PROCESSED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.RESULT_FAILED
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.RESULT_SENT
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.TAG_CHANNEL
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.TAG_REASON
import no.nav.budstikka.infrastructure.metrics.MicrometerDispatchMetrics.Companion.TAG_RESULT

class MicrometerDispatchMetricsTest :
    FunSpec({
        test("counts domain events under shared meter names with low-cardinality labels") {
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

            registry.get(INBOX_MESSAGE_CLAIMED).counter().count() shouldBe 3.0
            registry.get(INBOX_MESSAGE_EMPTY_POLLS).counter().count() shouldBe 1.0
            registry.get(INBOX_MESSAGE_PROCESSED).counter().count() shouldBe 1.0
            registry
                .get(INBOX_MESSAGE_DROPPED)
                .tag(TAG_REASON, "dead")
                .counter()
                .count() shouldBe 1.0
            registry.get(INBOX_MESSAGE_FAILED).counter().count() shouldBe 1.0
            registry.get(DELIVERY_CLAIMED).counter().count() shouldBe 2.0
            registry.get(DELIVERY_EMPTY_POLLS).counter().count() shouldBe 1.0
            registry
                .get(DELIVERY)
                .tag(TAG_CHANNEL, "microfrontend")
                .tag(TAG_RESULT, RESULT_SENT)
                .counter()
                .count() shouldBe 1.0
            registry
                .get(DELIVERY)
                .tag(TAG_CHANNEL, "brev")
                .tag(TAG_RESULT, RESULT_FAILED)
                .counter()
                .count() shouldBe 1.0
        }
    })
