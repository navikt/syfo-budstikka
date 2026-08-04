package no.nav.budstikka.e2e

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import no.nav.budstikka.testsupport.BudstikkaTestApp

@Tags("E2E")
class DeadLetterReplayE2ESpec :
    FunSpec({
        test("application boots through the real DI graph when dead-letter replay is enabled") {
            BudstikkaTestApp
                .start(
                    configOverrides =
                        mapOf(
                            "deadLetterReplay.enabled" to "true",
                            "deadLetterReplay.batchSize" to "100",
                        ),
                ).use { app ->
                    val connectors =
                        runBlocking {
                            app.server
                                .engine
                                .resolvedConnectors()
                        }
                    val hasResolvedConnector = connectors.isNotEmpty()
                    hasResolvedConnector shouldBe true
                }
        }
    })
