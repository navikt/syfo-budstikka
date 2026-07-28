package no.nav.budstikka.infrastructure.auth.config

import io.ktor.server.config.ApplicationConfig
import no.nav.budstikka.infrastructure.config.configFor
import no.nav.budstikka.infrastructure.config.validate

/**
 * Texas configuration read from platform-injected environment variables (no hard-coded URLs or secrets).
 * [tokenEndpoint] comes from `NAIS_TOKEN_ENDPOINT` when `azure`/`tokenx` is enabled in the NAIS manifest.
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
