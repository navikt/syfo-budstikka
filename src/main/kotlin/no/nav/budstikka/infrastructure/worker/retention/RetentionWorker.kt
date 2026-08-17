package no.nav.budstikka.infrastructure.worker.retention

import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.retention.RetentionMetrics
import no.nav.budstikka.application.retention.RetentionRepository
import no.nav.budstikka.application.retention.RetentionResult
import org.slf4j.LoggerFactory

class RetentionWorker(
    private val cleanup: RetentionRepository,
    private val batchSize: Int,
    private val metrics: RetentionMetrics,
) {
    private val logger = LoggerFactory.getLogger(RetentionWorker::class.java)

    suspend fun runOnce() {
        when (val result = cleanup.run(batchSize)) {
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
