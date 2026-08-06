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
                "narmesteLeder.url" to "http://narmesteleder.teamsykmelding",
                "narmesteLeder.scope" to "api://dev-gcp.teamsykmelding.narmesteleder/.default",
            ).toNarmesteLederConfig() shouldBe
                NarmesteLederConfig(
                    "http://narmesteleder.teamsykmelding",
                    "api://dev-gcp.teamsykmelding.narmesteleder/.default",
                )
        }

        test("toNarmesteLederConfig validates required values") {
            shouldThrow<IllegalStateException> {
                MapApplicationConfig("narmesteLeder.url" to "", "narmesteLeder.scope" to "").toNarmesteLederConfig()
            }.message shouldBe
                "Invalid configuration: narmesteLeder.url must be set (NARMESTELEDER_URL), narmesteLeder.scope must be set (NARMESTELEDER_SCOPE)"
        }
    })
