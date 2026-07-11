package no.nav.budstikka.infrastructure.task.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import java.time.Duration

class ConfigTest :
    FunSpec({
        test("toTaskConfig reads inbox-message and delivery settings") {
            val config =
                config(
                    intervalSeconds = "10",
                    batchSize = "50",
                    leaseSeconds = "120",
                    leaseBudgetFraction = "0.5",
                    deliveryIntervalSeconds = "7",
                    deliveryBatchSize = "30",
                    deliveryLeaseSeconds = "90",
                    deliveryLeaseBudgetFraction = "0.6",
                ).toTaskConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(10)
            config.inboxMessage.batchSize shouldBe 50
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(120)
            config.inboxMessage.leaseBudgetFraction shouldBe 0.5
            config.delivery.interval shouldBe Duration.ofSeconds(7)
            config.delivery.batchSize shouldBe 30
            config.delivery.leaseDuration shouldBe Duration.ofSeconds(90)
            config.delivery.leaseBudgetFraction shouldBe 0.6
        }

        test("toTaskConfig falls back to defaults when unset") {
            val config =
                config(
                    intervalSeconds = "",
                    batchSize = "",
                    leaseSeconds = "",
                    leaseBudgetFraction = "",
                    deliveryIntervalSeconds = "",
                    deliveryBatchSize = "",
                    deliveryLeaseSeconds = "",
                    deliveryLeaseBudgetFraction = "",
                ).toTaskConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS)
            config.inboxMessage.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_LEASE_SECONDS)
            config.inboxMessage.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
            config.delivery.interval shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS)
            config.delivery.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.delivery.leaseDuration shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_LEASE_SECONDS)
            config.delivery.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
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

        test("toTaskConfig validates lease budget fraction is within (0.0, 1.0]") {
            shouldThrow<IllegalStateException> {
                config(leaseBudgetFraction = "1.5").toTaskConfig()
            }.message shouldBe "Invalid tasks configuration: tasks.inboxMessage.leaseBudgetFraction must be a number in (0.0, 1.0]"
        }

        test("toTaskConfig validates delivery interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(deliveryIntervalSeconds = "0").toTaskConfig()
            }.message shouldBe "Invalid tasks configuration: tasks.delivery.intervalSeconds must be a positive integer"
        }
    })

private fun config(
    intervalSeconds: String = "5",
    batchSize: String = "100",
    leaseSeconds: String = "300",
    leaseBudgetFraction: String = "0.8",
    deliveryIntervalSeconds: String = "",
    deliveryBatchSize: String = "",
    deliveryLeaseSeconds: String = "",
    deliveryLeaseBudgetFraction: String = "",
): MapApplicationConfig =
    MapApplicationConfig(
        "tasks.inboxMessage.intervalSeconds" to intervalSeconds,
        "tasks.inboxMessage.batchSize" to batchSize,
        "tasks.inboxMessage.leaseSeconds" to leaseSeconds,
        "tasks.inboxMessage.leaseBudgetFraction" to leaseBudgetFraction,
        "tasks.delivery.intervalSeconds" to deliveryIntervalSeconds,
        "tasks.delivery.batchSize" to deliveryBatchSize,
        "tasks.delivery.leaseSeconds" to deliveryLeaseSeconds,
        "tasks.delivery.leaseBudgetFraction" to deliveryLeaseBudgetFraction,
    )
