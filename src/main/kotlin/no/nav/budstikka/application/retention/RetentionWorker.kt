package no.nav.budstikka.application.retention

import no.nav.budstikka.application.logging.applicationLogger

class RetentionWorker(
    private val repository: RetentionRepository,
    private val batchSize: Int,
    private val metrics: RetentionMetrics,
) {
    private val logger = applicationLogger(RetentionWorker::class.java)

    suspend fun runOnce() {
        when (val result = repository.run(batchSize)) {
            is RetentionResult.Completed -> {
                metrics.completed(result.counts)
                logger.info(
                    "Retention cleanup completed",
                    mapOf(
                        "inbox_deleted" to result.counts.inboxMessages,
                        "dead_letter_deleted" to result.counts.deadLetterMessages,
                        "delivery_deleted" to result.counts.deliveries,
                    ),
                )
            }

            RetentionResult.SkippedDueToLockContention -> {
                metrics.lockContention()
                logger.info("Retention cleanup skipped because another instance holds the advisory lock")
            }
        }
    }
}
