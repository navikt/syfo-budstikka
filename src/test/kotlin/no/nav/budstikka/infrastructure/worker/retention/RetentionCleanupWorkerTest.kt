package no.nav.budstikka.infrastructure.worker.retention

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCleanupCounts
import no.nav.budstikka.application.retention.RetentionCleanupMetrics
import no.nav.budstikka.application.retention.RetentionCleanupRepository
import no.nav.budstikka.application.retention.RetentionCleanupResult

class RetentionCleanupWorkerTest :
    FunSpec({
        test("reports completed cleanup counts to the metrics port") {
            val metrics = RecordingRetentionCleanupMetrics()
            val worker =
                RetentionCleanupWorker(
                    cleanup =
                        RetentionCleanupRepository {
                            RetentionCleanupResult.Completed(
                                RetentionCleanupCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4),
                            )
                        },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.completedCounts shouldBe
                listOf(RetentionCleanupCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4))
        }

        test("reports advisory-lock contention to the metrics port") {
            val metrics = RecordingRetentionCleanupMetrics()
            val worker =
                RetentionCleanupWorker(
                    cleanup = RetentionCleanupRepository { RetentionCleanupResult.SkippedDueToLockContention },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.lockContentions shouldBe 1
        }
    }) {
    private class RecordingRetentionCleanupMetrics : RetentionCleanupMetrics {
        val completedCounts = mutableListOf<RetentionCleanupCounts>()
        var lockContentions = 0

        override fun completed(counts: RetentionCleanupCounts) {
            completedCounts += counts
        }

        override fun lockContention() {
            lockContentions++
        }
    }
}
