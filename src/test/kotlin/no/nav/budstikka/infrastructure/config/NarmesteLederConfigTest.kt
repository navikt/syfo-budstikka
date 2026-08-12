package no.nav.budstikka.infrastructure.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.infrastructure.client.config.NarmesteLederConfig
import no.nav.budstikka.infrastructure.client.config.toNarmesteLederConfig

class NarmesteLederConfigTest :
    FunSpec({
        test("toNarmesteLederConfig reads platform values") {
            MapApplicationConfig(
                "narmesteLeder.url" to "http://esyfo-narmesteleder.team-esyfo",
                "narmesteLeder.scope" to "api://dev-gcp.team-esyfo.esyfo-narmesteleder/.default",
            ).toNarmesteLederConfig() shouldBe
                NarmesteLederConfig(
                    "http://esyfo-narmesteleder.team-esyfo",
                    "api://dev-gcp.team-esyfo.esyfo-narmesteleder/.default",
                )
        }

        test("toNarmesteLederConfig validates required values") {
            shouldThrow<IllegalStateException> {
                MapApplicationConfig("narmesteLeder.url" to "", "narmesteLeder.scope" to "").toNarmesteLederConfig()
            }.message shouldBe
                "Invalid configuration: narmesteLeder.url must be set (NARMESTELEDER_URL), narmesteLeder.scope must be set (NARMESTELEDER_SCOPE)"
        }
    })
