package no.nav.budstikka.infrastructure.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import no.nav.budstikka.application.retention.RetentionCleanupCounts
import no.nav.budstikka.application.retention.RetentionCleanupMetrics

/**
 * Micrometer adapter for [RetentionCleanupMetrics]. Counts cleanup outcomes in the shared registry.
 * Labels are fixed, low-cardinality, and PII-free.
 */
class MicrometerRetentionCleanupMetrics(
    private val registry: MeterRegistry,
) : RetentionCleanupMetrics {
    private val completedRuns = counter(COMPLETED_RUNS)
    private val lockContentions = counter(LOCK_CONTENTIONS)
    private val deletedRows =
        mapOf(
            TABLE_INBOX to deletedRowsCounter(TABLE_INBOX),
            TABLE_DEAD_LETTER to deletedRowsCounter(TABLE_DEAD_LETTER),
            TABLE_DELIVERY to deletedRowsCounter(TABLE_DELIVERY),
        )

    override fun completed(counts: RetentionCleanupCounts) {
        completedRuns.increment()
        deletedRows.getValue(TABLE_INBOX).increment(counts.inboxMessages.toDouble())
        deletedRows.getValue(TABLE_DEAD_LETTER).increment(counts.deadLetterMessages.toDouble())
        deletedRows.getValue(TABLE_DELIVERY).increment(counts.deliveries.toDouble())
    }

    override fun lockContention() = lockContentions.increment()

    private fun counter(name: String): Counter = Counter.builder(name).register(registry)

    private fun deletedRowsCounter(table: String): Counter = Counter.builder(DELETED_ROWS).tag(TAG_TABLE, table).register(registry)

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
