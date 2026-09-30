package no.nav.budstikka.application.retention

data class RetentionCounts(
    val inboxMessages: Int,
    val deadLetterMessages: Int,
    val deliveries: Int,
    val unprocessedInboxMessages: Int = 0,
)

sealed interface RetentionResult {
    data class Completed(
        val counts: RetentionCounts,
    ) : RetentionResult

    data object SkippedDueToLockContention : RetentionResult
}
