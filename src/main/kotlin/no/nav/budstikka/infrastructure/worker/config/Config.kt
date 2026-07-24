package no.nav.budstikka.infrastructure.worker.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate
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

private fun ApplicationConfig.leaseDrainConfig(prefix: String) =
    with(configFor(prefix)) {
        LeaseDrainConfig(
            interval =
                this("intervalSeconds").toLongOrNull()?.takeIf { it > 0 }?.seconds
                    ?: LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS.seconds,
            batchSize =
                this("batchSize").toIntOrNull()?.takeIf { it > 0 }
                    ?: LeaseDrainConfig.DEFAULT_BATCH_SIZE,
            leaseDuration =
                this("leaseSeconds").toLongOrNull()?.takeIf { it > 0 }?.seconds
                    ?: LeaseDrainConfig.DEFAULT_LEASE_SECONDS.seconds,
            leaseBudgetFraction =
                this("leaseBudgetFraction").toDoubleOrNull()?.takeIf { it > 0 && it <= 1 }
                    ?: LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION,
            maxAttempts =
                this("maxAttempts").toIntOrNull()?.takeIf { it > 0 }
                    ?: LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS,
            maxConsecutiveItemFailures =
                this("maxConsecutiveItemFailures").toIntOrNull()?.takeIf { it > 0 }
                    ?: LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES,
        ).validate {
            buildList {
                val raw = this@with
                if (raw("intervalSeconds").isNotBlank() &&
                    raw("intervalSeconds")
                        .toLongOrNull()
                        ?.takeIf { it > 0 } == null
                ) {
                    add("$prefix.intervalSeconds must be a positive integer")
                }
                if (raw("batchSize").isNotBlank() && raw("batchSize").toIntOrNull()?.takeIf { it > 0 } == null) {
                    add("$prefix.batchSize must be a positive integer")
                }
                if (raw("leaseSeconds").isNotBlank() && raw("leaseSeconds").toIntOrNull()?.takeIf { it > 0 } == null) {
                    add("$prefix.leaseSeconds must be a positive integer")
                }
                if (raw("leaseBudgetFraction").isNotBlank() &&
                    raw("leaseBudgetFraction").toDoubleOrNull()?.takeIf { it > 0 && it <= 1 } == null
                ) {
                    add("$prefix.leaseBudgetFraction must be a number in (0.0, 1.0]")
                }
                if (raw("maxAttempts").isNotBlank() && raw("maxAttempts").toIntOrNull()?.takeIf { it > 0 } == null) {
                    add("$prefix.maxAttempts must be a positive integer")
                }
                if (raw("maxConsecutiveItemFailures").isNotBlank() &&
                    raw("maxConsecutiveItemFailures").toIntOrNull()?.takeIf { it > 0 } == null
                ) {
                    add("$prefix.maxConsecutiveItemFailures must be a positive integer")
                }
            }
        }
    }
