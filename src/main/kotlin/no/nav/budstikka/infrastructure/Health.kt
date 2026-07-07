package no.nav.budstikka.infrastructure

data class HealthResult(
    val healthy: Boolean,
    val message: String,
)

interface HealthCheck {
    suspend fun check(): HealthResult
}

/**
 * Liveness signal for the pod's is_alive probe. Reports whether the Kafka consumer loop(s) are
 * still cycling. Must never depend on broker availability or consumer lag; see docs/HELSESJEKK.md.
 */
fun interface LivenessCheck {
    fun isAlive(): Boolean
}
