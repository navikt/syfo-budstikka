package no.nav.budstikka.infrastructure.auth.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

/**
 * Texas-oppsett lest fra plattform-injiserte miljøvariabler (ingen hardkodede URL-er/secrets).
 * [tokenEndpoint] kommer fra `NAIS_TOKEN_ENDPOINT` når `azure`/`tokenx` er aktivert i NAIS-manifestet.
 */
data class TexasConfig(
    val tokenEndpoint: String,
    val identityProvider: String,
)

fun ApplicationConfig.toTexasConfig() =
    with(configFor("auth.texas")) {
        TexasConfig(
            tokenEndpoint = this("tokenEndpoint"),
            identityProvider = this("identityProvider").ifBlank { DEFAULT_IDENTITY_PROVIDER },
        ).validate {
            buildList {
                if (it.tokenEndpoint.isBlank()) add("auth.texas.tokenEndpoint must be set (NAIS_TOKEN_ENDPOINT)")
            }
        }
    }

private const val DEFAULT_IDENTITY_PROVIDER = "entra_id"
