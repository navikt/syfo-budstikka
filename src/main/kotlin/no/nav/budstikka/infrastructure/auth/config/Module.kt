package no.nav.budstikka.infrastructure.auth.config

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.infrastructure.auth.TexasTokenProvider
import no.nav.budstikka.infrastructure.auth.TokenProvider

fun DependencyRegistry.authModule() {
    provide<HttpClient> { HttpClient(CIO) }.cleanup(HttpClient::close)
    provide<TokenProvider> {
        TexasTokenProvider(
            httpClient = resolve(),
            config = resolve(),
        )
    }
}
