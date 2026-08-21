package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.FERDIGSTILL_WITHOUT_MATCH
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.FERDIGSTILL_WITHOUT_SUPPORTED_RUNTIME_CHANNEL
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.FERDIGSTILL_WAITING_FOR_CREATE_SENT
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.FERDIGSTILL_WITH_FAILED_CREATE
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.FERDIGSTILL_WITH_INVALID_STORED_CREATE
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_MESSAGE_CLAIMED
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_MESSAGE_DROPPED
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_MESSAGE_EMPTY_POLLS
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_MESSAGE_FAILED
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_MESSAGE_PROCESSED
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.INBOX_OUTSIDE_SENDING_WINDOW
import no.nav.budstikka.infrastructure.metrics.MicrometerInboxMetrics.Companion.TAG_REASON

class MicrometerInboxMetricsTest :
    FunSpec({
        test("counts inbox events under established meter names with low-cardinality labels") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerInboxMetrics(registry)

            metrics.claimed(3)
            metrics.emptyPoll()
            metrics.processed()
            metrics.dropped(DropReason.DEAD)
            metrics.failed()
            metrics.outsideSendingWindow("Closed Sunday")
            metrics.ferdigstillWithoutMatch()
            metrics.ferdigstillWithoutSupportedRuntimeChannel()
            metrics.ferdigstillWaitingForCreateSent()
            metrics.ferdigstillWithFailedCreate()
            metrics.ferdigstillWithInvalidStoredCreate()

            registry.get(INBOX_MESSAGE_CLAIMED).counter().count() shouldBe 3.0
            registry.get(INBOX_MESSAGE_EMPTY_POLLS).counter().count() shouldBe 1.0
            registry.get(INBOX_MESSAGE_PROCESSED).counter().count() shouldBe 1.0
            registry
                .get(INBOX_MESSAGE_DROPPED)
                .tag(TAG_REASON, "dead")
                .counter()
                .count() shouldBe 1.0
            registry.get(INBOX_MESSAGE_FAILED).counter().count() shouldBe 1.0
            registry.get(INBOX_OUTSIDE_SENDING_WINDOW).counter().count() shouldBe 1.0
            registry.get(FERDIGSTILL_WITHOUT_MATCH).counter().count() shouldBe 1.0
            registry.get(FERDIGSTILL_WITHOUT_SUPPORTED_RUNTIME_CHANNEL).counter().count() shouldBe 1.0
            registry.get(FERDIGSTILL_WAITING_FOR_CREATE_SENT).counter().count() shouldBe 1.0
            registry.get(FERDIGSTILL_WITH_FAILED_CREATE).counter().count() shouldBe 1.0
            registry.get(FERDIGSTILL_WITH_INVALID_STORED_CREATE).counter().count() shouldBe 1.0
        }
    })
