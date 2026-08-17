package no.nav.budstikka.infrastructure.metrics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.nav.budstikka.application.retention.RetentionCleanupCounts
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.COMPLETED_RUNS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.DELETED_ROWS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.LOCK_CONTENTIONS
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.TABLE_DEAD_LETTER
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.TABLE_DELIVERY
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.TABLE_INBOX
import no.nav.budstikka.infrastructure.metrics.MicrometerRetentionCleanupMetrics.Companion.TAG_TABLE

class MicrometerRetentionCleanupMetricsTest :
    FunSpec({
        test("counts completed cleanup rows under established names and fixed PII-free table labels") {
            val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val metrics = MicrometerRetentionCleanupMetrics(registry)

            metrics.completed(RetentionCleanupCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4))

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
            val metrics = MicrometerRetentionCleanupMetrics(registry)

            metrics.lockContention()

            registry.get(LOCK_CONTENTIONS).counter().count() shouldBe 1.0
        }
    })
