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
) {
    init {
        require(batchSize > 0) { "batchSize must be greater than 0" }
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 5L
        const val DEFAULT_BATCH_SIZE = 100
    }
}

data class TaskConfig(
    val inboxMessage: InboxMessageTaskConfig,
)

fun ApplicationConfig.toTaskConfig(): TaskConfig {
    fun value(key: String): String = stringOrEmpty("tasks.$key").trim()

    val inboxIntervalSeconds = value("inboxMessage.intervalSeconds")
    val inboxBatchSize = value("inboxMessage.batchSize")

    val errors =
        buildList {
            if (inboxIntervalSeconds.isNotBlank() && (inboxIntervalSeconds.toLongOrNull()?.takeIf { it > 0 } == null)) {
                add("tasks.inboxMessage.intervalSeconds must be a positive integer")
            }
            if (inboxBatchSize.isNotBlank() && (inboxBatchSize.toIntOrNull()?.takeIf { it > 0 } == null)) {
                add("tasks.inboxMessage.batchSize must be a positive integer")
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
            ),
    )
}
