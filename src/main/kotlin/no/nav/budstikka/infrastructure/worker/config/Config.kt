package no.nav.budstikka.infrastructure.worker.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty
import java.time.Duration

// Operational knobs for the claim-lease-drain workers (inbox and delivery), resolved from
// application.conf like KafkaConfig and DatabaseConfig so intervals and batch sizes are env-tunable
// without a redeploy. Both workers share the same shape, so they share [LeaseDrainConfig] (the
// value type lives in `application`; this file owns only the HOCON parsing).

data class WorkerConfig(
    val inboxMessage: LeaseDrainConfig,
    val delivery: LeaseDrainConfig,
)

fun ApplicationConfig.toWorkerConfig(): WorkerConfig {
    fun value(key: String): String = stringOrEmpty("workers.$key").trim()

    val inboxIntervalSeconds = value("inboxMessage.intervalSeconds")
    val inboxBatchSize = value("inboxMessage.batchSize")
    val inboxLeaseSeconds = value("inboxMessage.leaseSeconds")
    val inboxLeaseBudgetFraction = value("inboxMessage.leaseBudgetFraction")
    val inboxMaxAttempts = value("inboxMessage.maxAttempts")
    val deliveryIntervalSeconds = value("delivery.intervalSeconds")
    val deliveryBatchSize = value("delivery.batchSize")
    val deliveryLeaseSeconds = value("delivery.leaseSeconds")
    val deliveryLeaseBudgetFraction = value("delivery.leaseBudgetFraction")
    val deliveryMaxAttempts = value("delivery.maxAttempts")

    val errors =
        buildList {
            validateWorkerConfig(
                errors = this,
                keyPrefix = "workers.inboxMessage",
                intervalSeconds = inboxIntervalSeconds,
                batchSize = inboxBatchSize,
                leaseSeconds = inboxLeaseSeconds,
                leaseBudgetFraction = inboxLeaseBudgetFraction,
                maxAttempts = inboxMaxAttempts,
            )
            validateWorkerConfig(
                errors = this,
                keyPrefix = "workers.delivery",
                intervalSeconds = deliveryIntervalSeconds,
                batchSize = deliveryBatchSize,
                leaseSeconds = deliveryLeaseSeconds,
                leaseBudgetFraction = deliveryLeaseBudgetFraction,
                maxAttempts = deliveryMaxAttempts,
            )
        }

    check(errors.isEmpty()) {
        "Invalid workers configuration: ${errors.joinToString(", ")}"
    }

    return WorkerConfig(
        inboxMessage =
            LeaseDrainConfig(
                interval =
                    Duration.ofSeconds(
                        inboxIntervalSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS,
                    ),
                batchSize =
                    inboxBatchSize.toIntOrNull()?.takeIf { it > 0 }
                        ?: LeaseDrainConfig.DEFAULT_BATCH_SIZE,
                leaseDuration =
                    Duration.ofSeconds(
                        inboxLeaseSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: LeaseDrainConfig.DEFAULT_LEASE_SECONDS,
                    ),
                leaseBudgetFraction =
                    inboxLeaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 }
                        ?: LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION,
                maxAttempts =
                    inboxMaxAttempts.toIntOrNull()?.takeIf { it > 0 }
                        ?: LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
            ),
        delivery =
            LeaseDrainConfig(
                interval =
                    Duration.ofSeconds(
                        deliveryIntervalSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS,
                    ),
                batchSize =
                    deliveryBatchSize.toIntOrNull()?.takeIf { it > 0 }
                        ?: LeaseDrainConfig.DEFAULT_BATCH_SIZE,
                leaseDuration =
                    Duration.ofSeconds(
                        deliveryLeaseSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: LeaseDrainConfig.DEFAULT_LEASE_SECONDS,
                    ),
                leaseBudgetFraction =
                    deliveryLeaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 }
                        ?: LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION,
                maxAttempts =
                    deliveryMaxAttempts.toIntOrNull()?.takeIf { it > 0 }
                        ?: LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
            ),
    )
}

private fun validateWorkerConfig(
    errors: MutableList<String>,
    keyPrefix: String,
    intervalSeconds: String,
    batchSize: String,
    leaseSeconds: String,
    leaseBudgetFraction: String,
    maxAttempts: String,
) {
    if (intervalSeconds.isNotBlank() && (intervalSeconds.toLongOrNull()?.takeIf { it > 0 } == null)) {
        errors += "$keyPrefix.intervalSeconds must be a positive integer"
    }
    if (batchSize.isNotBlank() && (batchSize.toIntOrNull()?.takeIf { it > 0 } == null)) {
        errors += "$keyPrefix.batchSize must be a positive integer"
    }
    if (leaseSeconds.isNotBlank() && (leaseSeconds.toLongOrNull()?.takeIf { it > 0 } == null)) {
        errors += "$keyPrefix.leaseSeconds must be a positive integer"
    }
    if (leaseBudgetFraction.isNotBlank() &&
        (leaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 } == null)
    ) {
        errors += "$keyPrefix.leaseBudgetFraction must be a number in (0.0, 1.0]"
    }
    if (maxAttempts.isNotBlank() && (maxAttempts.toIntOrNull()?.takeIf { it > 0 } == null)) {
        errors += "$keyPrefix.maxAttempts must be a positive integer"
    }
}
