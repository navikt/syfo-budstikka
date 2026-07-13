package no.nav.budstikka.infrastructure.worker.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.application.LeaseDrainConfig
import kotlin.time.Duration.Companion.seconds

class ConfigTest :
    FunSpec({
        test("toWorkerConfig reads inbox-message and delivery settings") {
            val config =
                config(
                    inboxIntervalSeconds = "10",
                    inboxBatchSize = "50",
                    inboxLeaseSeconds = "120",
                    inboxLeaseBudgetFraction = "0.5",
                    inboxMaxAttempts = "7",
                    inboxMaxConsecutiveItemFailures = "4",
                    deliveryIntervalSeconds = "7",
                    deliveryBatchSize = "30",
                    deliveryLeaseSeconds = "90",
                    deliveryLeaseBudgetFraction = "0.6",
                    deliveryMaxAttempts = "8",
                    deliveryMaxConsecutiveItemFailures = "5",
                ).toWorkerConfig()

            config.inboxMessage.interval shouldBe 10.seconds
            config.inboxMessage.batchSize shouldBe 50
            config.inboxMessage.leaseDuration shouldBe 120.seconds
            config.inboxMessage.leaseBudgetFraction shouldBe 0.5
            config.inboxMessage.maxAttempts shouldBe 7
            config.inboxMessage.maxConsecutiveItemFailures shouldBe 4
            config.delivery.interval shouldBe 7.seconds
            config.delivery.batchSize shouldBe 30
            config.delivery.leaseDuration shouldBe 90.seconds
            config.delivery.leaseBudgetFraction shouldBe 0.6
            config.delivery.maxAttempts shouldBe 8
            config.delivery.maxConsecutiveItemFailures shouldBe 5
        }

        test("toWorkerConfig falls back to defaults when unset") {
            val config =
                config(
                    inboxIntervalSeconds = "",
                    inboxBatchSize = "",
                    inboxLeaseSeconds = "",
                    inboxLeaseBudgetFraction = "",
                    inboxMaxAttempts = "",
                    inboxMaxConsecutiveItemFailures = "",
                    deliveryIntervalSeconds = "",
                    deliveryBatchSize = "",
                    deliveryLeaseSeconds = "",
                    deliveryLeaseBudgetFraction = "",
                    deliveryMaxAttempts = "",
                    deliveryMaxConsecutiveItemFailures = "",
                ).toWorkerConfig()

            config.inboxMessage.interval shouldBe LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS.seconds
            config.inboxMessage.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.inboxMessage.leaseDuration shouldBe LeaseDrainConfig.DEFAULT_LEASE_SECONDS.seconds
            config.inboxMessage.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
            config.inboxMessage.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
            config.inboxMessage.maxConsecutiveItemFailures shouldBe LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES
            config.delivery.interval shouldBe LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS.seconds
            config.delivery.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
            config.delivery.leaseDuration shouldBe LeaseDrainConfig.DEFAULT_LEASE_SECONDS.seconds
            config.delivery.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
            config.delivery.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
            config.delivery.maxConsecutiveItemFailures shouldBe LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES
        }

        test("toWorkerConfig validates interval is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(inboxIntervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.intervalSeconds must be a positive integer"
        }

        test("toWorkerConfig validates batch size is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(inboxBatchSize = "-1").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.batchSize must be a positive integer"
        }

        test("toWorkerConfig validates lease is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(inboxLeaseSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.leaseSeconds must be a positive integer"
        }

        test("toWorkerConfig validates lease budget fraction is within (0.0, 1.0]") {
            shouldThrow<IllegalArgumentException> {
                config(inboxLeaseBudgetFraction = "1.5").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.leaseBudgetFraction must be a number in (0.0, 1.0]"
        }

        test("toWorkerConfig validates max attempts is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(inboxMaxAttempts = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.maxAttempts must be a positive integer"
        }

        test("toWorkerConfig validates delivery interval is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(deliveryIntervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.delivery.intervalSeconds must be a positive integer"
        }

        test("toWorkerConfig validates max consecutive item failures is a positive integer") {
            shouldThrow<IllegalArgumentException> {
                config(inboxMaxConsecutiveItemFailures = "0").toWorkerConfig()
            }.message shouldBe "Invalid workers configuration: workers.inboxMessage.maxConsecutiveItemFailures must be a positive integer"
        }
    })

private fun config(
    inboxIntervalSeconds: String = "5",
    inboxBatchSize: String = "100",
    inboxLeaseSeconds: String = "300",
    inboxLeaseBudgetFraction: String = "0.8",
    inboxMaxAttempts: String = "5",
    inboxMaxConsecutiveItemFailures: String = "3",
    deliveryIntervalSeconds: String = "",
    deliveryBatchSize: String = "",
    deliveryLeaseSeconds: String = "",
    deliveryLeaseBudgetFraction: String = "",
    deliveryMaxAttempts: String = "",
    deliveryMaxConsecutiveItemFailures: String = "",
): MapApplicationConfig =
    MapApplicationConfig(
        "workers.inboxMessage.intervalSeconds" to inboxIntervalSeconds,
        "workers.inboxMessage.batchSize" to inboxBatchSize,
        "workers.inboxMessage.leaseSeconds" to inboxLeaseSeconds,
        "workers.inboxMessage.leaseBudgetFraction" to inboxLeaseBudgetFraction,
        "workers.inboxMessage.maxAttempts" to inboxMaxAttempts,
        "workers.inboxMessage.maxConsecutiveItemFailures" to inboxMaxConsecutiveItemFailures,
        "workers.delivery.intervalSeconds" to deliveryIntervalSeconds,
        "workers.delivery.batchSize" to deliveryBatchSize,
        "workers.delivery.leaseSeconds" to deliveryLeaseSeconds,
        "workers.delivery.leaseBudgetFraction" to deliveryLeaseBudgetFraction,
        "workers.delivery.maxAttempts" to deliveryMaxAttempts,
        "workers.delivery.maxConsecutiveItemFailures" to deliveryMaxConsecutiveItemFailures,
    )
