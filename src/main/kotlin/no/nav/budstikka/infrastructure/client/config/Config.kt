package no.nav.budstikka.infrastructure.client.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

data class PdlConfig(
    val url: String,
    val scope: String,
    val behandlingsnummer: String,
)

fun ApplicationConfig.toPdlConfig() =
    with(configFor("pdl")) {
        PdlConfig(
            url = this("url"),
            scope = this("scope"),
            behandlingsnummer = this("behandlingsnummer"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("pdl.url must be set (PDL_URL)")
                if (config.scope.isBlank()) add("pdl.scope must be set (PDL_SCOPE)")
                if (config.behandlingsnummer.isBlank()) add("pdl.behandlingsnummer must be set (PDL_BEHANDLINGSNUMMER)")
            }
        }
    }

data class DocumentDistributorConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toDocumentDistributorConfig() =
    with(configFor("documentDistributor")) {
        DocumentDistributorConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("documentDistributor.url must be set (DOCUMENT_DISTRIBUTOR_URL)")
                if (config.scope.isBlank()) add("documentDistributor.scope must be set (DOCUMENT_DISTRIBUTOR_SCOPE)")
            }
        }
    }

/**
 * KRR-oppsett (digdir-krr-proxy) for reservasjonsgaten (ADR 0009). [url] er endepunktet
 * reservasjonsstatus slås opp mot; [scope] er Entra ID-target-en
 * [no.nav.budstikka.infrastructure.auth.TokenProvider] veksler M2M-token mot. Begge injiseres av
 * plattformen (ingen hardkodede secrets).
 */
data class KrrConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toKrrConfig() =
    with(configFor("krr")) {
        KrrConfig(
            url = this("url"),
            scope = this("scope"),
        ).validate { config ->
            buildList {
                if (config.url.isBlank()) add("krr.url must be set (KRR_URL)")
                if (config.scope.isBlank()) add("krr.scope must be set (KRR_SCOPE)")
            }
        }
    }
