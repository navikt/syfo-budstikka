package no.nav.budstikka.application

import kotlin.time.Duration

/**
 * Operational settings for one claim-lease drain worker (inbox or delivery). Pure value type in
 * `application`; parsing from `application.conf` belongs in `infrastructure.worker.config`, so
 * workers do not depend on Ktor configuration. A future cleanup worker carries a retention window,
 * not batch size/lease, and receives its own type.
 */
data class LeaseDrainConfig(
    val interval: Duration,
    val batchSize: Int,
    val leaseDuration: Duration,
    val leaseBudgetFraction: Double,
    val maxAttempts: Int,
    val maxConsecutiveItemFailures: Int,
) {
    init {
        require(batchSize > 0) { "batchSize must be greater than 0" }
        require(leaseDuration.isPositive()) { "leaseDuration must be positive" }
        require(leaseBudgetFraction > 0.0 && leaseBudgetFraction <= 1.0) {
            "leaseBudgetFraction must be in (0.0, 1.0]"
        }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        require(maxConsecutiveItemFailures > 0) {
            "maxConsecutiveItemFailures must be greater than 0"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_BATCH_SIZE = 25
        const val DEFAULT_LEASE_SECONDS = 300L
        const val DEFAULT_LEASE_BUDGET_FRACTION = 0.8
        const val DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES = 3

        // Terminal gate for poison rows (#71): a row claimed this many times without reaching a
        // terminal state becomes FAILED instead of being reclaimed forever.
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
