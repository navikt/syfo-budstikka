package no.nav.budstikka.infrastructure.auth.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.stringOrEmpty

/**
 * Texas-oppsett lest fra plattform-injiserte miljøvariabler (ingen hardkodede URL-er/secrets).
 * [tokenEndpoint] kommer fra `NAIS_TOKEN_ENDPOINT` når `azure`/`tokenx` er aktivert i NAIS-manifestet.
 */
data class TexasConfig(
    val tokenEndpoint: String,
    val identityProvider: String,
)

fun ApplicationConfig.toTexasConfig(): TexasConfig {
    fun value(key: String): String = stringOrEmpty("auth.texas.$key").trim()

    val tokenEndpoint = value("tokenEndpoint")
    val identityProvider = value("identityProvider").ifBlank { DEFAULT_IDENTITY_PROVIDER }

    val errors =
        buildList {
            if (tokenEndpoint.isBlank()) add("auth.texas.tokenEndpoint must be set (NAIS_TOKEN_ENDPOINT)")
        }

    check(errors.isEmpty()) {
        "Invalid Texas configuration: ${errors.joinToString(", ")}"
    }

    return TexasConfig(
        tokenEndpoint = tokenEndpoint,
        identityProvider = identityProvider,
    )
}

private const val DEFAULT_IDENTITY_PROVIDER = "entra_id"
