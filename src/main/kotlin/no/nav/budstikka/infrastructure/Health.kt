package no.nav.budstikka.infrastructure

data class HealthResult(
    val healthy: Boolean,
    val message: String,
)

interface HealthCheck {
    suspend fun check(): HealthResult
}

/**
 * Liveness signal for the pod's is_alive probe. Reports whether every background loop — the Kafka
 * consumer runner(s) and the background workers — is still cycling. Must never depend on broker
 * availability, consumer lag or processing success; see docs/helsesjekk.md.
 */
fun interface LivenessCheck {
    fun isAlive(): Boolean
}
