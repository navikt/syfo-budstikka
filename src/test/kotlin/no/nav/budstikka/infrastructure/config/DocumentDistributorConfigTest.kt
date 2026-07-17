package no.nav.budstikka.infrastructure.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.server.config.MapApplicationConfig
import no.nav.budstikka.infrastructure.client.config.DocumentDistributorConfig
import no.nav.budstikka.infrastructure.client.config.toDocumentDistributorConfig

class DocumentDistributorConfigTest :
    FunSpec({
        test("toDocumentDistributorConfig reads nais platform values") {
            val config =
                config(
                    url = "prod-gcp",
                    scope = "team-esyfo",
                ).toDocumentDistributorConfig()

            config shouldBe
                DocumentDistributorConfig(
                    url = "prod-gcp",
                    scope = "team-esyfo",
                )
        }

        test("toDocumentDistributorConfig validates required values") {
            shouldThrow<IllegalStateException> {
                config(url = "", scope = "").toDocumentDistributorConfig()
            }.message shouldBe
                "Invalid document distributor configuration: " +
                "documentDistributor.url must be set (DOCUMENT_DISTRIBUTOR_URL), documentDistributor.scope must be set (DOCUMENT_DISTRIBUTOR_SCOPE)"
        }
    })

private fun config(
    url: String = "test-url",
    scope: String = "my-scope",
): MapApplicationConfig =
    MapApplicationConfig(
        "documentDistributor.url" to url,
        "documentDistributor.scope" to scope,
    )
