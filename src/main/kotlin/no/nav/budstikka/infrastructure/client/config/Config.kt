package no.nav.budstikka.infrastructure.client.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty

/**
 * PDL-oppsett for død-oppslaget (#52). [url] er GraphQL-endepunktet til pdl-api; [scope] er
 * Entra ID-target-en (`api://<cluster>.pdl.pdl-api/.default`) [no.nav.budstikka.infrastructure.auth.TokenProvider]
 * veksler M2M-token mot. Begge injiseres av plattformen (ingen hardkodede secrets).
 */
data class PdlConfig(
    val url: String,
    val scope: String,
)

fun ApplicationConfig.toPdlConfig(): PdlConfig {
    fun value(key: String): String = stringOrEmpty("pdl.$key").trim()

    val url = value("url")
    val scope = value("scope")

    val errors =
        buildList {
            if (url.isBlank()) add("pdl.url must be set (PDL_URL)")
            if (scope.isBlank()) add("pdl.scope must be set (PDL_SCOPE)")
        }

    check(errors.isEmpty()) {
        "Invalid PDL configuration: ${errors.joinToString(", ")}"
    }

    return PdlConfig(url = url, scope = scope)
}
