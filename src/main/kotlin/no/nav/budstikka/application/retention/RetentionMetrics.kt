package no.nav.budstikka.application.retention

/**
 * Counting-only metrics port: implementations must not throw or perform I/O. Labels are fixed,
 * low-cardinality, and PII-free.
 */
interface RetentionMetrics {
    fun completed(counts: RetentionCounts)

    fun lockContention()
}

object NoRetentionMetrics : RetentionMetrics {
    override fun completed(counts: RetentionCounts) = Unit

    override fun lockContention() = Unit
}
