package no.nav.budstikka.infrastructure.task.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty
import java.time.Duration

// Operational knobs for the background tasks, resolved from application.conf like KafkaConfig and
// DatabaseConfig so intervals and batch sizes are env-tunable without a redeploy. Each task keeps
// its own settings type because the knobs differ (a cleanup task will carry a retention window, not
// a batch size).

data class InboxMessageTaskConfig(
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
    val inboxMessage: InboxMessageTaskConfig,
)

fun ApplicationConfig.toTaskConfig(): TaskConfig {
    fun value(key: String): String = stringOrEmpty("tasks.$key").trim()

    val inboxIntervalSeconds = value("inboxMessage.intervalSeconds")
    val inboxBatchSize = value("inboxMessage.batchSize")
    val inboxLeaseSeconds = value("inboxMessage.leaseSeconds")
    val inboxLeaseBudgetFraction = value("inboxMessage.leaseBudgetFraction")

    val errors =
        buildList {
            if (inboxIntervalSeconds.isNotBlank() && (inboxIntervalSeconds.toLongOrNull()?.takeIf { it > 0 } == null)) {
                add("tasks.inboxMessage.intervalSeconds must be a positive integer")
            }
            if (inboxBatchSize.isNotBlank() && (inboxBatchSize.toIntOrNull()?.takeIf { it > 0 } == null)) {
                add("tasks.inboxMessage.batchSize must be a positive integer")
            }
            if (inboxLeaseSeconds.isNotBlank() && (inboxLeaseSeconds.toLongOrNull()?.takeIf { it > 0 } == null)) {
                add("tasks.inboxMessage.leaseSeconds must be a positive integer")
            }
            if (inboxLeaseBudgetFraction.isNotBlank() &&
                (inboxLeaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 } == null)
            ) {
                add("tasks.inboxMessage.leaseBudgetFraction must be a number in (0.0, 1.0]")
            }
        }

    check(errors.isEmpty()) {
        "Invalid tasks configuration: ${errors.joinToString(", ")}"
    }

    return TaskConfig(
        inboxMessage =
            InboxMessageTaskConfig(
                interval =
                    Duration.ofSeconds(
                        inboxIntervalSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: InboxMessageTaskConfig.DEFAULT_INTERVAL_SECONDS,
                    ),
                batchSize =
                    inboxBatchSize.toIntOrNull()?.takeIf { it > 0 }
                        ?: InboxMessageTaskConfig.DEFAULT_BATCH_SIZE,
                leaseDuration =
                    Duration.ofSeconds(
                        inboxLeaseSeconds.toLongOrNull()?.takeIf { it > 0 }
                            ?: InboxMessageTaskConfig.DEFAULT_LEASE_SECONDS,
                    ),
                leaseBudgetFraction =
                    inboxLeaseBudgetFraction.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 }
                        ?: InboxMessageTaskConfig.DEFAULT_LEASE_BUDGET_FRACTION,
            ),
    )
}
