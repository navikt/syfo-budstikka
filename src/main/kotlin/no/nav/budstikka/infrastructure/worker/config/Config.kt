package no.nav.budstikka.infrastructure.worker.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty
import kotlin.time.Duration.Companion.seconds

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
    val maxConsecutiveItemFailures = stringOrEmpty("$prefix.maxConsecutiveItemFailures").trim()

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
            if (maxConsecutiveItemFailures.isNotBlank() && maxConsecutiveItemFailures.toIntOrNull()?.takeIf { it > 0 } == null) {
                add("$prefix.maxConsecutiveItemFailures must be a positive integer")
            }
        }

    require(errors.isEmpty()) {
        "Invalid workers configuration: ${errors.joinToString(", ")}"
    }

    return LeaseDrainConfig(
        interval =
            (intervalSeconds.toLongOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS).seconds,
        batchSize = batchSize.toIntOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_BATCH_SIZE,
        leaseDuration =
            (leaseSeconds.toLongOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_LEASE_SECONDS).seconds,
        leaseBudgetFraction =
            leaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0 && it <= 1 }
                ?: LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION,
        maxAttempts = maxAttempts.toIntOrNull()?.takeIf { it > 0 } ?: LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
        maxConsecutiveItemFailures =
            maxConsecutiveItemFailures.toIntOrNull()?.takeIf { it > 0 }

                ?: LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
    )
}
