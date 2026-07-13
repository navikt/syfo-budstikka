package no.nav.budstikka.infrastructure.worker.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import java.time.Duration

class ConfigTest :
    FunSpec({
        test("toWorkerConfig reads inbox-message and delivery settings") {
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
                    deliveryMaxAttempts = "8",
                ).toWorkerConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(10)
            config.inboxMessage.batchSize shouldBe 50
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(120)
            config.inboxMessage.leaseBudgetFraction shouldBe 0.5
            config.inboxMessage.maxAttempts shouldBe 5
            config.delivery.interval shouldBe Duration.ofSeconds(7)
            config.delivery.batchSize shouldBe 30
            config.delivery.leaseDuration shouldBe Duration.ofSeconds(90)
            config.delivery.leaseBudgetFraction shouldBe 0.6
            config.delivery.maxAttempts shouldBe 8
        }

        test("toWorkerConfig falls back to defaults when unset") {
            val config =
                config(
                    intervalSeconds = "",
                    batchSize = "",
                    leaseSeconds = "",
                    leaseBudgetFraction = "",
                    maxAttempts = "",
                    deliveryIntervalSeconds = "",
                    deliveryBatchSize = "",
                    deliveryLeaseSeconds = "",
                    deliveryLeaseBudgetFraction = "",
                    deliveryMaxAttempts = "",
                ).toWorkerConfig()

            config.inboxMessage.interval shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS)
            config.inboxMessage.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.inboxMessage.leaseDuration shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_LEASE_SECONDS)
            config.inboxMessage.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
            config.inboxMessage.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
            config.delivery.interval shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS)
            config.delivery.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.delivery.leaseDuration shouldBe Duration.ofSeconds(LeaseDrainConfig.DEFAULT_LEASE_SECONDS)
            config.delivery.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
            config.delivery.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
        }

        test("toWorkerConfig validates interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(intervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.intervalSeconds must be a positive integer"
        }

        test("toWorkerConfig validates batch size is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(batchSize = "-1").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.batchSize must be a positive integer"
        }

        test("toWorkerConfig validates lease is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(leaseSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.leaseSeconds must be a positive integer"
        }

        test("toWorkerConfig validates lease budget fraction is within (0.0, 1.0]") {
            shouldThrow<IllegalStateException> {
                config(leaseBudgetFraction = "1.5").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.leaseBudgetFraction must be a number in (0.0, 1.0]"
        }

        test("toWorkerConfig validates max attempts is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(maxAttempts = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.maxAttempts must be a positive integer"
        }

        test("toWorkerConfig validates delivery interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(deliveryIntervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.delivery.intervalSeconds must be a positive integer"
        }
    })

private fun config(
    intervalSeconds: String = "5",
    batchSize: String = "100",
    leaseSeconds: String = "300",
    leaseBudgetFraction: String = "0.8",
    maxAttempts: String = "5",
    deliveryIntervalSeconds: String = "",
    deliveryBatchSize: String = "",
    deliveryLeaseSeconds: String = "",
    deliveryLeaseBudgetFraction: String = "",
    deliveryMaxAttempts: String = "",
): MapApplicationConfig =
    MapApplicationConfig(
        "workers.inboxMessage.intervalSeconds" to intervalSeconds,
        "workers.inboxMessage.batchSize" to batchSize,
        "workers.inboxMessage.leaseSeconds" to leaseSeconds,
        "workers.inboxMessage.leaseBudgetFraction" to leaseBudgetFraction,
        "workers.inboxMessage.maxAttempts" to maxAttempts,
        "workers.delivery.intervalSeconds" to deliveryIntervalSeconds,
        "workers.delivery.batchSize" to deliveryBatchSize,
        "workers.delivery.leaseSeconds" to deliveryLeaseSeconds,
        "workers.delivery.leaseBudgetFraction" to deliveryLeaseBudgetFraction,
        "workers.delivery.maxAttempts" to deliveryMaxAttempts,
    )
