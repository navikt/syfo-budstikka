package no.nav.budstikka.infrastructure.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig

class PlatformConfigTest :
    FunSpec({
        test("toPlatformConfig reads nais platform values") {
            val config =
                config(
                    clusterName = "prod-gcp",
                    namespace = "team-esyfo",
                    appName = "syfo-budstikka",
                ).toPlatformConfig()

            config shouldBe
                PlatformConfig(
                    clusterName = "prod-gcp",
                    namespace = "team-esyfo",
                    appName = "syfo-budstikka",
                )
        }

        test("toPlatformConfig validates required values") {
            shouldThrow<IllegalStateException> {
                config(clusterName = "", namespace = "", appName = "").toPlatformConfig()
            }.message shouldBe
                "Invalid configuration: " +
                "nais.clusterName must be set, nais.namespace must be set, nais.appName must be set"
        }
    })

private fun config(
    clusterName: String = "dev-gcp",
    namespace: String = "team-esyfo",
    appName: String = "syfo-budstikka",
): MapApplicationConfig =
    MapApplicationConfig(
        "nais.clusterName" to clusterName,
        "nais.namespace" to namespace,
        "nais.appName" to appName,
    )
