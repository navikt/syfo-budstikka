package no.nav.budstikka.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class HealthTest :
    FunSpec({
        test("checkHealth gir ready når alle sjekker er friske") {
            checkHealth(
                listOf(
                    healthyCheck("Database"),
                    healthyCheck("Cache"),
                ),
            ) shouldBe
                HealthResult(
                    healthy = true,
                    message = "I'm ready! :)",
                )
        }

        test("checkHealth gir feilmelding når noen sjekker feiler") {
            checkHealth(
                listOf(
                    healthyCheck("Database"),
                    unhealthyCheck("Cache unavailable"),
                    unhealthyCheck("Queue unavailable"),
                ),
            ) shouldBe
                HealthResult(
                    healthy = false,
                    message = "Cache unavailable, Queue unavailable",
                )
        }
    })

private fun healthyCheck(message: String): HealthCheck =
    object : HealthCheck {
        override suspend fun check(): HealthResult =
            HealthResult(
                healthy = true,
                message = message,
            )
    }

private fun unhealthyCheck(message: String): HealthCheck =
    object : HealthCheck {
        override suspend fun check(): HealthResult =
            HealthResult(
                healthy = false,
                message = message,
            )
    }
