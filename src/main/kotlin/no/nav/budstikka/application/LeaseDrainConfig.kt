package no.nav.budstikka.application

import java.time.Duration

/**
 * Operasjonelle knotter for én claim-lease-drain-worker (inbox eller delivery). Ren verdi-type i
 * `application`; parsing fra `application.conf` bor i `infrastructure.worker.config` slik at
 * workerne ikke avhenger av Ktor-config. En framtidig cleanup-worker bærer et retensjonsvindu (ikke
 * batchSize/lease) og får sin egen type.
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
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
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

        // Terminal-gate mot poison rows (#71): en rad som er claimet så mange ganger uten å nå
        // terminal status blir markert FAILED i stedet for å reclaimes for alltid.
        const val DEFAULT_MAX_ATTEMPTS = 10
    }
}
