package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.COMPLETED_RUNS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.DELETED_ROWS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.LOCK_CONTENTIONS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.TABLE_DEAD_LETTER
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.TABLE_DELIVERY
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.TABLE_INBOX
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionMetrics.Companion.TAG_TABLE

class MicrometerRetentionMetricsTest :
    FunSpec({
        test("counts completed cleanup rows under established names and fixed PII-free table labels") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerRetentionMetrics(registry)

            metrics.completed(RetentionCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4))

            registry.get(COMPLETED_RUNS).counter().count() shouldBe 1.0
            registry
                .get(DELETED_ROWS)
                .tag(TAG_TABLE, TABLE_INBOX)
                .counter()
                .count() shouldBe 2.0
            registry
                .get(DELETED_ROWS)
                .tag(TAG_TABLE, TABLE_DEAD_LETTER)
                .counter()
                .count() shouldBe 3.0
            registry
                .get(DELETED_ROWS)
                .tag(TAG_TABLE, TABLE_DELIVERY)
                .counter()
                .count() shouldBe 4.0
        }

        test("counts advisory-lock contention under the established meter name") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerRetentionMetrics(registry)

            metrics.lockContention()

            registry.get(LOCK_CONTENTIONS).counter().count() shouldBe 1.0
        }
    })
