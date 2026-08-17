package no.nav.budstikka.application.retention

import kotlin.time.Duration

data class RetentionConfig(
    val enabled: Boolean,
    val interval: Duration,
    val batchSize: Int,
) {
    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 3600L
        const val MAXIMUM_BATCH_SIZE = 100
    }
}
