package no.nav.budstikka.infrastructure.auth.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.infrastructure.auth.TexasTokenProvider
import no.nav.budstikka.infrastructure.auth.TokenProvider

/**
 * DI for den utgående token-sømmen (#48). Registrerer en delt utgående [HttpClient] og
 * [TokenProvider] (Texas). Kanal-klientene (f.eks. PdlClient, #52) resolver [TokenProvider]
 * og gjenbruker den delte [HttpClient].
 *
 * DI er lat: [TexasConfig] valideres (og krever `NAIS_TOKEN_ENDPOINT`) først når noe faktisk
 * resolver [TokenProvider]. Lokalt/test — der død-oppslaget kjøres mot en fake — resolves den
 * aldri, så ingen Texas-sidecar eller ekte token kreves (jf. B51).
 */
fun DependencyRegistry.authModule() {
    provide<HttpClient> { HttpClient(CIO) }.cleanup(HttpClient::close)
    provide<TokenProvider> {
        TexasTokenProvider(
            httpClient = resolve(),
            config = resolve(),
        )
    }
}
