package no.nav.budstikka.infrastructure.replay.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

class ConfigTest :
    FunSpec({
        test("toDeadLetterReplayConfig reads enabled and batch size") {
            config(enabled = "true", batchSize = "25").toDeadLetterReplayConfig() shouldBe
                DeadLetterReplayConfig(enabled = true, batchSize = 25)
        }

        test("toDeadLetterReplayConfig validates batch size is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(batchSize = "0").toDeadLetterReplayConfig()
            }.message shouldBe "Invalid configuration: deadLetterReplay.batchSize must be a positive integer"
        }

        test("toDeadLetterReplayConfig validates enabled") {
            shouldThrow<IllegalStateException> {
                config(enabled = "yes").toDeadLetterReplayConfig()
            }.message shouldBe "Invalid configuration: deadLetterReplay.enabled must be true or false"
        }
    })

private fun config(
    enabled: String = "false",
    batchSize: String = "100",
): MapApplicationConfig =
    MapApplicationConfig(
        "deadLetterReplay.enabled" to enabled,
        "deadLetterReplay.batchSize" to batchSize,
    )
