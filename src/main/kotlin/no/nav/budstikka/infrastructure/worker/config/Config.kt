package no.nav.budstikka.infrastructure.worker.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty
import java.time.Duration

// Operational knobs for the claim-lease workers (inbox and delivery), resolved from
// application.conf like KafkaConfig and DatabaseConfig so intervals and batch sizes are env-tunable
// without a redeploy. Both workers share the same shape, so they share [LeaseDrainConfig] (the
// value type lives in `application`; this file owns only the HOCON parsing).

data class WorkerConfig(
    val inboxMessage: LeaseDrainConfig,
    val delivery: LeaseDrainConfig,
)

fun ApplicationConfig.toWorkerConfig(): WorkerConfig =
    WorkerConfig(
        inboxMessage = leaseDrainConfig("workers.inboxMessage"),
        delivery = leaseDrainConfig("workers.delivery"),
    )

private fun ApplicationConfig.leaseDrainConfig(prefix: String): LeaseDrainConfig {
    val intervalSeconds = stringOrEmpty("$prefix.intervalSeconds").trim()
    val batchSize = stringOrEmpty("$prefix.batchSize").trim()
    val leaseSeconds = stringOrEmpty("$prefix.leaseSeconds").trim()
    val leaseBudgetFraction = stringOrEmpty("$prefix.leaseBudgetFraction").trim()
    val maxAttempts = stringOrEmpty("$prefix.maxAttempts").trim()

    val errors =
        buildList {
            if (intervalSeconds.isNotBlank() && intervalSeconds.toLongOrNull()?.takeIf { it > 0 } == null) {
                add("$prefix.intervalSeconds must be a positive integer")
            }
            if (batchSize.isNotBlank() && batchSize.toIntOrNull()?.takeIf { it > 0 } == null) {
                add("$prefix.batchSize must be a positive integer")
            }
            if (leaseSeconds.isNotBlank() && leaseSeconds.toLongOrNull()?.takeIf { it > 0 } == null) {
                add("$prefix.leaseSeconds must be a positive integer")
            }
            if (leaseBudgetFraction.isNotBlank() && leaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0 && it <= 1 } == null) {
                add("$prefix.leaseBudgetFraction must be a number in (0.0, 1.0]")
            }
            if (maxAttempts.isNotBlank() && maxAttempts.toIntOrNull()?.takeIf { it > 0 } == null) {
                add("$prefix.maxAttempts must be a positive integer")
            }
        }

    require(errors.isEmpty()) {
        "Invalid workers configuration: ${errors.joinToString(", ")}"
    }

    return LeaseDrainConfig(
        interval =
            Duration.ofSeconds(
                intervalSeconds.toLongOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS,
            ),
        batchSize = batchSize.toIntOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_BATCH_SIZE,
        leaseDuration =
            Duration.ofSeconds(
                leaseSeconds.toLongOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_LEASE_SECONDS,
            ),
        leaseBudgetFraction =
            leaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0 && it <= 1 }
                ?: LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION,
        maxAttempts = maxAttempts.toIntOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
    )
}
