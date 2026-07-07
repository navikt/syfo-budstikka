package no.nav.budstikka.infrastructure

data class HealthResult(
    val healthy: Boolean,
    val message: String,
)

interface HealthCheck {
    suspend fun check(): HealthResult
}

suspend fun checkHealth(checks: List<HealthCheck>): HealthResult {
    val results = checks.map { it.check() }

    return if (results.all { it.healthy }) {
        HealthResult(
            healthy = true,
            message = "I'm ready! :)",
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
