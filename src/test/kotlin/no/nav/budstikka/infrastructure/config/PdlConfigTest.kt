package no.nav.budstikka.infrastructure.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.infrastructure.client.config.PdlConfig
import no.nav.budstikka.infrastructure.client.config.toPdlConfig

class PdlConfigTest :
    FunSpec({
        test("toPdlConfig reads nais platform values") {
            val config =
                config(
                    url = "prod-gcp",
                    scope = "team-esyfo",
                    behandlingsnummer = "123456789",
                ).toPdlConfig()

            config shouldBe
                PdlConfig(
                    url = "prod-gcp",
                    scope = "team-esyfo",
                    behandlingsnummer = "123456789",
                )
        }

        test("toPdlConfig validates required values") {
            shouldThrow<IllegalStateException> {
                config(url = "", scope = "", behandlingsnummer = "").toPdlConfig()
            }.message shouldBe
                "Invalid PDL configuration: " +
                "pdl.url must be set (PDL_URL), pdl.scope must be set (PDL_SCOPE), pdl.behandlingsnummer must be set (PDL_BEHANDLINGSNUMMER)"
        }
    })

private fun config(
    url: String = "test-url",
    scope: String = "my-scope",
    behandlingsnummer: String = "123456789",
): MapApplicationConfig =
    MapApplicationConfig(
        "pdl.url" to url,
        "pdl.scope" to scope,
        "pdl.behandlingsnummer" to behandlingsnummer,
    )
