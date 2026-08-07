package no.nav.budstikka.infrastructure.replay.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

data class DeadLetterReplayConfig(
    val enabled: Boolean,
    val batchSize: Int,
)

fun ApplicationConfig.toDeadLetterReplayConfig(): DeadLetterReplayConfig =
    with(configFor("deadLetterReplay")) {
        val enabled = this("enabled")
        val batchSize = this("batchSize").toIntOrNull()?.takeIf { it > 0 } ?: 100
        DeadLetterReplayConfig(
            enabled = enabled.equals("true", ignoreCase = true),
            batchSize = batchSize,
        ).validate {
            buildList {
                if (enabled.isNotEmpty() && !enabled.equals("true", ignoreCase = true) && !enabled.equals("false", ignoreCase = true)) {
                    add("deadLetterReplay.enabled must be true or false")
                }
                if (this@with("batchSize").isNotBlank() &&
                    this@with("batchSize").toIntOrNull()?.takeIf { it > 0 } == null
                ) {
                    add("deadLetterReplay.batchSize must be a positive integer")
                }
            }
        }
    }
