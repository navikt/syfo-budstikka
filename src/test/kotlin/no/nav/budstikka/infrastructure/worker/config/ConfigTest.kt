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
            with(
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
                ).toWorkerConfig(),
            ) {
                inboxMessage.interval shouldBe 10.seconds
                inboxMessage.batchSize shouldBe 50
                inboxMessage.leaseDuration shouldBe 120.seconds
                inboxMessage.leaseBudgetFraction shouldBe 0.5
                inboxMessage.maxAttempts shouldBe 7
                inboxMessage.maxConsecutiveItemFailures shouldBe 4
                delivery.interval shouldBe 7.seconds
                delivery.batchSize shouldBe 30
                delivery.leaseDuration shouldBe 90.seconds
                delivery.leaseBudgetFraction shouldBe 0.6
                delivery.maxAttempts shouldBe 8
                delivery.maxConsecutiveItemFailures shouldBe 5
            }
        }

        test("toWorkerConfig falls back to defaults when unset") {
            with(
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
                ).toWorkerConfig(),
            ) {
                inboxMessage.interval shouldBe LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS.seconds
                inboxMessage.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
                inboxMessage.leaseDuration shouldBe LeaseDrainConfig.DEFAULT_LEASE_SECONDS.seconds
                inboxMessage.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
                inboxMessage.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
                inboxMessage.maxConsecutiveItemFailures shouldBe LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES
                delivery.interval shouldBe LeaseDrainConfig.DEFAULT_INTERVAL_SECONDS.seconds
                delivery.batchSize shouldBe LeaseDrainConfig.DEFAULT_BATCH_SIZE
                delivery.leaseDuration shouldBe LeaseDrainConfig.DEFAULT_LEASE_SECONDS.seconds
                delivery.leaseBudgetFraction shouldBe LeaseDrainConfig.DEFAULT_LEASE_BUDGET_FRACTION
                delivery.maxAttempts shouldBe LeaseDrainConfig.DEFAULT_MAX_ATTEMPTS
                delivery.maxConsecutiveItemFailures shouldBe LeaseDrainConfig.DEFAULT_MAX_CONSECUTIVE_ITEM_FAILURES
            }
        }

        test("toWorkerConfig validates interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(inboxIntervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.intervalSeconds must be a positive integer"
        }

        test("toWorkerConfig validates batch size is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(inboxBatchSize = "-1").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.batchSize must be a positive integer"
        }

        test("toWorkerConfig validates lease is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(inboxLeaseSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.leaseSeconds must be a positive integer"
        }

        test("toWorkerConfig validates lease budget fraction is within (0.0, 1.0]") {
            shouldThrow<IllegalStateException> {
                config(inboxLeaseBudgetFraction = "1.5").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.leaseBudgetFraction must be a number in (0.0, 1.0]"
        }

        test("toWorkerConfig validates max attempts is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(inboxMaxAttempts = "0").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.maxAttempts must be a positive integer"
        }

        test("toWorkerConfig validates delivery interval is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(deliveryIntervalSeconds = "0").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.delivery.intervalSeconds must be a positive integer"
        }

        test("toWorkerConfig validates max consecutive item failures is a positive integer") {
            shouldThrow<IllegalStateException> {
                config(inboxMaxConsecutiveItemFailures = "0").toWorkerConfig()
            }.message shouldBe "Invalid configuration: workers.inboxMessage.maxConsecutiveItemFailures must be a positive integer"
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
