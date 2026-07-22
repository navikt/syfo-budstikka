package no.nav.budstikka.infrastructure.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.infrastructure.client.config.KrrConfig
import no.nav.budstikka.infrastructure.client.config.toKrrConfig

class KrrConfigTest :
    FunSpec({
        test("toKrrConfig reads nais platform values") {
            val config =
                config(
                    url = "https://digdir-krr-proxy/rest/v1/person",
                    scope = "api://dev-gcp.team-rocket.digdir-krr-proxy/.default",
                ).toKrrConfig()

            config shouldBe
                KrrConfig(
                    url = "https://digdir-krr-proxy/rest/v1/person",
                    scope = "api://dev-gcp.team-rocket.digdir-krr-proxy/.default",
                )
        }

        test("toKrrConfig validates required values") {
            shouldThrow<IllegalStateException> {
                config(url = "", scope = "").toKrrConfig()
            }.message shouldBe
                "Invalid KRR configuration: krr.url must be set (KRR_URL), krr.scope must be set (KRR_SCOPE)"
        }
    })

private fun config(
    url: String = "test-url",
    scope: String = "my-scope",
): MapApplicationConfig =
    MapApplicationConfig(
        "krr.url" to url,
        "krr.scope" to scope,
    )
