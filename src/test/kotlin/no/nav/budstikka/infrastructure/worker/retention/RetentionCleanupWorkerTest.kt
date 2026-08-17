package no.nav.budstikka.infrastructure.worker.retention

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.budstikka.application.retention.RetentionCleanupCounts
import no.nav.budstikka.application.retention.RetentionCleanupRepository
import no.nav.budstikka.application.retention.RetentionCleanupResult
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.COMPLETED_RUNS
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.DELETED_ROWS
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.LOCK_CONTENTIONS
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.TABLE_DELIVERY
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.TABLE_INBOX
import no.nav.budstikka.infrastructure.worker.retention.RetentionCleanupWorker.Companion.TAG_TABLE

class RetentionCleanupWorkerTest :
    FunSpec({
        test("records completed cleanup rows with fixed PII-free table labels") {
            val registry = SimpleMeterRegistry()
            val worker =
                RetentionCleanupWorker(
                    cleanup =
                        RetentionCleanupRepository {
                            RetentionCleanupResult.Completed(
                                RetentionCleanupCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4),
                            )
                        },
                    batchSize = 100,
                    meterRegistry = registry,
                )

            worker.runOnce()

            registry.get(COMPLETED_RUNS).counter().count() shouldBe 1.0
            registry
                .get(DELETED_ROWS)
                .tag(TAG_TABLE, TABLE_INBOX)
                .counter()
                .count() shouldBe 2.0
            registry
                .get(DELETED_ROWS)
                .tag(TAG_TABLE, TABLE_DELIVERY)
                .counter()
                .count() shouldBe 4.0
        }

        test("records advisory-lock contention as a skipped cleanup outcome") {
            val registry = SimpleMeterRegistry()
            val worker =
                RetentionCleanupWorker(
                    cleanup = RetentionCleanupRepository { RetentionCleanupResult.SkippedDueToLockContention },
                    batchSize = 100,
                    meterRegistry = registry,
                )

            worker.runOnce()

            registry.get(LOCK_CONTENTIONS).counter().count() shouldBe 1.0
        }
    })
