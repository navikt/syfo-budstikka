package no.nav.budstikka.infrastructure.worker.retention

import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.retention.RetentionCleanupMetrics
import no.nav.budstikka.application.retention.RetentionCleanupRepository
import no.nav.budstikka.application.retention.RetentionCleanupResult
import org.slf4j.LoggerFactory

class RetentionCleanupWorker(
    private val cleanup: RetentionCleanupRepository,
    private val batchSize: Int,
    private val metrics: RetentionCleanupMetrics,
) {
    private val logger = LoggerFactory.getLogger(RetentionCleanupWorker::class.java)

    suspend fun runOnce() {
        when (val result = cleanup.run(batchSize)) {
            is RetentionCleanupResult.Completed -> {
                metrics.completed(result.counts)
                logger.info(
                    "Retention cleanup completed {} {} {}",
                    kv("inbox_deleted", result.counts.inboxMessages),
                    kv("dead_letter_deleted", result.counts.deadLetterMessages),
                    kv("delivery_deleted", result.counts.deliveries),
                )
            }

            RetentionCleanupResult.SkippedDueToLockContention -> {
                metrics.lockContention()
                logger.info("Retention cleanup skipped because another instance holds the advisory lock")
            }
        }
    }
}
