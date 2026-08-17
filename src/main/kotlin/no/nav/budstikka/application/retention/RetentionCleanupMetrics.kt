package no.nav.budstikka.application.retention

/**
 * Counting-only metrics port: implementations must not throw or perform I/O. Labels are fixed,
 * low-cardinality, and PII-free.
 */
interface RetentionCleanupMetrics {
    fun completed(counts: RetentionCleanupCounts)

    fun lockContention()
}

object NoRetentionCleanupMetrics : RetentionCleanupMetrics {
    override fun completed(counts: RetentionCleanupCounts) = Unit

    override fun lockContention() = Unit
}
