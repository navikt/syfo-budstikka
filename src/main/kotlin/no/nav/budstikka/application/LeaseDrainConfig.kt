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
) {
    init {
        require(batchSize > 0) { "batchSize must be greater than 0" }
        require(!leaseDuration.isZero && !leaseDuration.isNegative) { "leaseDuration must be positive" }
        require(leaseBudgetFraction > 0.0 && leaseBudgetFraction <= 1.0) {
            "leaseBudgetFraction must be in (0.0, 1.0]"
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_BATCH_SIZE = 25
        const val DEFAULT_LEASE_SECONDS = 300L
        const val DEFAULT_LEASE_BUDGET_FRACTION = 0.8
    }
}
