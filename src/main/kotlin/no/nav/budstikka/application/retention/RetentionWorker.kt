package no.nav.budstikka.application.retention

import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.LoggerFactory

class RetentionWorker(
    private val repository: RetentionRepository,
    private val batchSize: Int,
    private val metrics: RetentionMetrics,
) {
    private val logger = LoggerFactory.getLogger(RetentionWorker::class.java)

    suspend fun runOnce() {
        when (val result = repository.run(batchSize)) {
            is RetentionResult.Completed -> {
                metrics.completed(result.counts)
                logger.info(
                    "Retention cleanup completed {} {} {}",
                    kv("inbox_deleted", result.counts.inboxMessages),
                    kv("dead_letter_deleted", result.counts.deadLetterMessages),
                    kv("delivery_deleted", result.counts.deliveries),
                )
            }

            RetentionResult.SkippedDueToLockContention -> {
                metrics.lockContention()
                logger.info("Retention cleanup skipped because another instance holds the advisory lock")
            }
        }
    }
}
