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

fun ApplicationConfig.toWorkerConfig(): WorkerConfig {
    val inboxMessage = readLeaseDrainWorkerConfig("workers.inboxMessage")
    val delivery = readLeaseDrainWorkerConfig("workers.delivery")

    val errors =
        buildList {
            addAll(inboxMessage.errors)
            addAll(delivery.errors)
        }

    check(errors.isEmpty()) {
        "Invalid workers configuration: ${errors.joinToString(", ")}"
    }

    return WorkerConfig(
        inboxMessage = inboxMessage.config,
        delivery = delivery.config,
    )
}

private data class ParsedLeaseDrainWorkerConfig(
    val config: LeaseDrainConfig,
    val errors: List<String>,
)

private fun ApplicationConfig.readLeaseDrainWorkerConfig(section: String): ParsedLeaseDrainWorkerConfig {
    val intervalSeconds = readWorkerValue(section, "intervalSeconds")
    val batchSize = readWorkerValue(section, "batchSize")
    val leaseSeconds = readWorkerValue(section, "leaseSeconds")
    val leaseBudgetFraction = readWorkerValue(section, "leaseBudgetFraction")
    val maxAttempts = readWorkerValue(section, "maxAttempts")

    val errors =
        buildList {
            validateLeaseDrainWorkerConfig(
                errors = this,
                keyPrefix = section,
                intervalSeconds = intervalSeconds,
                batchSize = batchSize,
                leaseSeconds = leaseSeconds,
                leaseBudgetFraction = leaseBudgetFraction,
                maxAttempts = maxAttempts,
            )
        }

    return ParsedLeaseDrainWorkerConfig(
        config =
            LeaseDrainConfig(
                interval =
                    Duration.ofSeconds(
                        intervalSeconds.positiveLongOrDefault(LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS),
                    ),
                batchSize =
                    batchSize.positiveIntOrDefault(LeaseDrainConfig.DEFAULT_BATCH_SIZE),
                leaseDuration =
                    Duration.ofSeconds(
                        leaseSeconds.positiveLongOrDefault(LeaseDrainConfig.DEFAULT_LEASE_SECONDS),
                    ),
                leaseBudgetFraction =
                    leaseBudgetFraction.positiveDoubleOrDefault(LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION),
                maxAttempts =
                    maxAttempts.positiveIntOrDefault(LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS),
            ),
        errors = errors,
    )
}

private fun validateLeaseDrainWorkerConfig(
    errors: MutableList<String>,
    keyPrefix: String,
    intervalSeconds: String,
    batchSize: String,
    leaseSeconds: String,
    leaseBudgetFraction: String,
    maxAttempts: String,
) {
    if (intervalSeconds.isNotBlank() && intervalSeconds.positiveLongOrNull() == null) {
        errors += "$keyPrefix.intervalSeconds must be a positive integer"
    }
    if (batchSize.isNotBlank() && batchSize.positiveIntOrNull() == null) {
        errors += "$keyPrefix.batchSize must be a positive integer"
    }
    if (leaseSeconds.isNotBlank() && leaseSeconds.positiveLongOrNull() == null) {
        errors += "$keyPrefix.leaseSeconds must be a positive integer"
    }
    if (leaseBudgetFraction.isNotBlank() && leaseBudgetFraction.positiveDoubleOrNull() == null) {
        errors += "$keyPrefix.leaseBudgetFraction must be a number in (0.0, 1.0]"
    }
    if (maxAttempts.isNotBlank() && maxAttempts.positiveIntOrNull() == null) {
        errors += "$keyPrefix.maxAttempts must be a positive integer"
    }
}

private fun ApplicationConfig.readWorkerValue(
    section: String,
    key: String,
): String = stringOrEmpty("$section.$key").trim()

private fun String.positiveLongOrNull(): Long? = toLongOrNull()?.takeIf { it > 0 }

private fun String.positiveIntOrNull(): Int? = toIntOrNull()?.takeIf { it > 0 }

private fun String.positiveDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf { it > 0.0 && it <= 1.0 }

private fun String.positiveLongOrDefault(defaultValue: Long): Long = positiveLongOrNull() ?: defaultValue

private fun String.positiveIntOrDefault(defaultValue: Int): Int = positiveIntOrNull() ?: defaultValue

private fun String.positiveDoubleOrDefault(defaultValue: Double): Double = positiveDoubleOrNull() ?: defaultValue
