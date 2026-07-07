package no.nav.syfo.no.nav.budstikka.infrastructure

data class HealthResult(
    val healthy: Boolean,
    val message: String,
)

typealias HealthCheck = suspend () -> HealthResult

suspend fun checkHealth(checks: List<HealthCheck>): HealthResult {
    val results = checks.map { it() }

    return if (results.all { it.healthy }) {
        HealthResult(
            healthy = true,
            message = "Ready",
        )
    } else {
        HealthResult(
            healthy = false,
            message =
                results
                    .filterNot { it.healthy }
                    .joinToString { it.message },
        )
    }
}
