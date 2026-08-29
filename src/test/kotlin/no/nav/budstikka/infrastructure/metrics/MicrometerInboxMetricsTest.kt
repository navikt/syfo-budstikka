package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.inbox.DeadLetterReason
import no.nav.budstikka.domain.decision.DropReason
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
        }

        test("counts persisted dead letters under one bounded reason label") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerInboxMetrics(registry)

            metrics.deadLetterPersisted(DeadLetterReason.MISSING_EVENT_ID, 2)
            metrics.deadLetterPersisted(DeadLetterReason.UNPARSEABLE_PAYLOAD, 1)

            DeadLetterReason.entries.forEach { reason ->
                val expected =
                    when (reason) {
                        DeadLetterReason.MISSING_EVENT_ID -> 2.0
                        DeadLetterReason.UNPARSEABLE_PAYLOAD -> 1.0
                        else -> 0.0
                    }
                registry
                    .get("inbox.dead.letter.persisted")
                    .tag(TAG_REASON, reason.metricTag)
                    .counter()
                    .count() shouldBe expected
            }
            registry.scrape() shouldContain
                "inbox_dead_letter_persisted_total{reason=\"missing_event_id\"} 2.0"
        }
    })
