package no.nav.budstikka.infrastructure.worker.retention

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.retention.RetentionCleanupRepository
import no.nav.budstikka.application.retention.RetentionCleanupResult
import org.slf4j.LoggerFactory

class RetentionCleanupWorker(
    private val cleanup: RetentionCleanupRepository,
    private val batchSize: Int,
    meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(RetentionCleanupWorker::class.java)
    private val completedRuns = Counter.builder(COMPLETED_RUNS).register(meterRegistry)
    private val lockContentions = Counter.builder(LOCK_CONTENTIONS).register(meterRegistry)
    private val deletedRows =
        mapOf(
            TABLE_INBOX to counter(meterRegistry, TABLE_INBOX),
            TABLE_DEAD_LETTER to counter(meterRegistry, TABLE_DEAD_LETTER),
            TABLE_DELIVERY to counter(meterRegistry, TABLE_DELIVERY),
        )

    suspend fun runOnce() {
        when (val result = cleanup.run(batchSize)) {
            is RetentionCleanupResult.Completed -> {
                completedRuns.increment()
                deletedRows.getValue(TABLE_INBOX).increment(result.counts.inboxMessages.toDouble())
                deletedRows.getValue(TABLE_DEAD_LETTER).increment(result.counts.deadLetterMessages.toDouble())
                deletedRows.getValue(TABLE_DELIVERY).increment(result.counts.deliveries.toDouble())
                logger.info(
                    "Retention cleanup completed {} {} {}",
                    kv("inbox_deleted", result.counts.inboxMessages),
                    kv("dead_letter_deleted", result.counts.deadLetterMessages),
                    kv("delivery_deleted", result.counts.deliveries),
                )
            }

            RetentionCleanupResult.SkippedDueToLockContention -> {
                lockContentions.increment()
                logger.info("Retention cleanup skipped because another instance holds the advisory lock")
            }
        }
    }

    private fun counter(
        meterRegistry: MeterRegistry,
        table: String,
    ): Counter = Counter.builder(DELETED_ROWS).tag(TAG_TABLE, table).register(meterRegistry)

    companion object {
        const val COMPLETED_RUNS = "retention.cleanup.completed"
        const val LOCK_CONTENTIONS = "retention.cleanup.lock.contention"
        const val DELETED_ROWS = "retention.cleanup.deleted"
        const val TAG_TABLE = "table"
        const val TABLE_INBOX = "inbox"
        const val TABLE_DEAD_LETTER = "dead_letter"
        const val TABLE_DELIVERY = "delivery"
    }
}
