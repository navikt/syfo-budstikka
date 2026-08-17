package no.nav.budstikka.application.retention

fun interface RetentionRepository {
    suspend fun run(batchSize: Int): RetentionResult
}
