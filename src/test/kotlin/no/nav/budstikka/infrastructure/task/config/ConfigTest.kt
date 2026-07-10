package no.nav.budstikka.infrastructure.task.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import java.time.Duration

class ConfigTest :
    FunSpec({
        test("toTaskConfig reads inbox-message interval and batch size") {
            val config = config(intervalSeconds = "10", batchSize = "50", leaseSeconds = "120").toTaskConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(10)
            config.inboxMessage.batchSize shouldBe 50
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(120)
        }

        test("toTaskConfig falls back to defaults when unset") {
            val config = config(intervalSeconds = "", batchSize = "", leaseSeconds = "").toTaskConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(InboxMessageTaskConfig.DEFAULT_INTERVAL_SECONDS)
            config.inboxMessage.batchSize shouldBe InboxMessageTaskConfig.DEFAULT_BATCH_SIZE
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(InboxMessageTaskConfig.DEFAULT_LEASE_SECONDS)
        }

        test("toTaskConfig validates interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(intervalSeconds = "0").toTaskConfig()
            }.message shouldBe "Invalid tasks configuration: tasks.inboxMessage.intervalSeconds must be a positive integer"
        }

        test("toTaskConfig validates batch size is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(batchSize = "-1").toTaskConfig()
            }.message shouldBe "Invalid tasks configuration: tasks.inboxMessage.batchSize must be a positive integer"
        }

        test("toTaskConfig validates lease is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(leaseSeconds = "0").toTaskConfig()
            }.message shouldBe "Invalid tasks configuration: tasks.inboxMessage.leaseSeconds must be a positive integer"
        }
    })

private fun config(
    intervalSeconds: String = "5",
    batchSize: String = "100",
    leaseSeconds: String = "300",
): MapApplicationConfig =
    MapApplicationConfig(
        "tasks.inboxMessage.intervalSeconds" to intervalSeconds,
        "tasks.inboxMessage.batchSize" to batchSize,
        "tasks.inboxMessage.leaseSeconds" to leaseSeconds,
    )
