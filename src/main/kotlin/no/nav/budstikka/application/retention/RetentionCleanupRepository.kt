package no.nav.budstikka.application.retention

data class RetentionCleanupCounts(
    val inboxMessages: Int,
    val deadLetterMessages: Int,
    val deliveries: Int,
)

sealed interface RetentionCleanupResult {
    data class Completed(
        val counts: RetentionCleanupCounts,
    ) : RetentionCleanupResult

    data object SkippedDueToLockContention : RetentionCleanupResult
}

fun interface RetentionCleanupRepository {
    suspend fun run(batchSize: Int): RetentionCleanupResult
}
