package no.nav.budstikka.application.retention

data class RetentionCounts(
    val inboxMessages: Int,
    val deadLetterMessages: Int,
    val deliveries: Int,
)

sealed interface RetentionResult {
    data class Completed(
        val counts: RetentionCounts,
    ) : RetentionResult

    data object SkippedDueToLockContention : RetentionResult
}

fun interface RetentionRepository {
    suspend fun run(batchSize: Int): RetentionResult
}
