package no.nav.budstikka.infrastructure.client.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty

data class PdlConfig(
    val url: String,
    val scope: String,
    val behandlingsnummer: String,
)

fun ApplicationConfig.toPdlConfig(): PdlConfig {
    fun value(key: String): String = stringOrEmpty("pdl.$key").trim()

    val url = value("url")
    val scope = value("scope")
    val behandlingsnummer = value("behandlingsnummer")

    val errors =
        buildList {
            if (url.isBlank()) add("pdl.url must be set (PDL_URL)")
            if (scope.isBlank()) add("pdl.scope must be set (PDL_SCOPE)")
            if (behandlingsnummer.isBlank()) add("pdl.behandlingsnummer must be set (PDL_BEHANDLINGSNUMMER)")
        }

    check(errors.isEmpty()) {
        "Invalid PDL configuration: ${errors.joinToString(", ")}"
    }

    return PdlConfig(url = url, scope = scope, behandlingsnummer = behandlingsnummer)
}

data class DocumentDistributorConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toDocumentDistributorConfig(): DocumentDistributorConfig {
    fun value(key: String): String = stringOrEmpty("documentDistributor.$key").trim()

    val url = value("url")
    val scope = value("scope")

    val errors =
        buildList {
            if (url.isBlank()) add("documentDistributor.url must be set (DOCUMENT_DISTRIBUTOR_URL)")
            if (scope.isBlank()) add("documentDistributor.scope must be set (DOCUMENT_DISTRIBUTOR_SCOPE)")
        }

    check(errors.isEmpty()) {
        "Invalid document distributor configuration: ${errors.joinToString(", ")}"
    }

    return DocumentDistributorConfig(url = url, scope = scope)
}
