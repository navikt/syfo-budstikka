package no.nav.budstikka.application.retention

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RetentionWorkerTest :
    FunSpec({
        test("reports completed cleanup counts to the metrics port") {
            val metrics = RecordingRetentionMetrics()
            val worker =
                RetentionWorker(
                    cleanup =
                        RetentionRepository {
                            RetentionResult.Completed(
                                RetentionCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4),
                            )
                        },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.completedCounts shouldBe
                listOf(RetentionCounts(inboxMessages = 2, deadLetterMessages = 3, deliveries = 4))
        }

        test("reports advisory-lock contention to the metrics port") {
            val metrics = RecordingRetentionMetrics()
            val worker =
                RetentionWorker(
                    cleanup = RetentionRepository { RetentionResult.SkippedDueToLockContention },
                    batchSize = 100,
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.lockContentions shouldBe 1
        }
    }) {
    private class RecordingRetentionMetrics : RetentionMetrics {
        val completedCounts = mutableListOf<RetentionCounts>()
        var lockContentions = 0

        override fun completed(counts: RetentionCounts) {
            completedCounts += counts
        }

        override fun lockContention() {
            lockContentions++
        }
    }
}
