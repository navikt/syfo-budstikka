package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.DELIVERY
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.DELIVERY_CLAIMED
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.DELIVERY_EMPTY_POLLS
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.NARMESTE_LEDER_MISSING
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.RESULT_FAILED
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.RESULT_SENT
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.TAG_CHANNEL
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.TAG_REASON
import no.nav.budstikka.infrastructure.metrics.MicrometerDeliveryMetrics.Companion.TAG_RESULT

class MicrometerDeliveryMetricsTest :
    FunSpec({
        test("counts delivery events under established meter names with low-cardinality labels") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerDeliveryMetrics(registry)

            metrics.claimed(2)
            metrics.emptyPoll()
            metrics.sent(Channel.MICROFRONTEND)
            metrics.failed(Channel.BREV)
            metrics.narmesteLederMissing(NarmesteLederMissingReason.MISSING_ACTIVE_LEADER)

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
            registry
                .get(NARMESTE_LEDER_MISSING)
                .tag(TAG_REASON, "missing_active_leader")
                .counter()
                .count() shouldBe 1.0
        }
    })
