package no.nav.budstikka.infrastructure.task.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty
import java.time.Duration

// Operational knobs for the claim-lease-drain tasks (inbox and delivery), resolved from
// application.conf like KafkaConfig and DatabaseConfig so intervals and batch sizes are env-tunable
// without a redeploy. Both workers share the same shape, so they share [LeaseDrainConfig]; a future
// cleanup task carries a retention window (not a batch size) and gets its own type.

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

data class TaskConfig(
    val inboxMessage: LeaseDrainConfig,
    val delivery: LeaseDrainConfig,
)

fun ApplicationConfig.toTaskConfig(): TaskConfig {
    fun value(key: String): String = stringOrEmpty("tasks.$key").trim()

    val inboxIntervalSeconds = value("inboxMessage.intervalSeconds")
    val inboxBatchSize = value("inboxMessage.batchSize")
    val inboxLeaseSeconds = value("inboxMessage.leaseSeconds")
    val inboxLeaseBudgetFraction = value("inboxMessage.leaseBudgetFraction")
    val deliveryIntervalSeconds = value("delivery.intervalSeconds")
    val deliveryBatchSize = value("delivery.batchSize")
    val deliveryLeaseSeconds = value("delivery.leaseSeconds")
    val deliveryLeaseBudgetFraction = value("delivery.leaseBudgetFraction")

    val errors =
        buildList {
            validateTaskConfig(
                errors = this,
                keyPrefix = "tasks.inboxMessage",
                intervalSeconds = inboxIntervalSeconds,
                batchSize = inboxBatchSize,
                leaseSeconds = inboxLeaseSeconds,
                leaseBudgetFraction = inboxLeaseBudgetFraction,
            )
            validateTaskConfig(
                errors = this,
                keyPrefix = "tasks.delivery",
                intervalSeconds = deliveryIntervalSeconds,
                batchSize = deliveryBatchSize,
                leaseSeconds = deliveryLeaseSeconds,
                leaseBudgetFraction = deliveryLeaseBudgetFraction,
            )
        }

    check(errors.isEmpty()) {
        "Invalid tasks configuration: ${errors.joinToString(", ")}"
    }

    return TaskConfig(
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
            ),
    )
}

private fun validateTaskConfig(
    errors: MutableList<String>,
    keyPrefix: String,
    intervalSeconds: String,
    batchSize: String,
    leaseSeconds: String,
    leaseBudgetFraction: String,
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
}
