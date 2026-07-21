package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.application.port.DocumentDistributor
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider

fun DependencyRegistry.clientModule() {
    provide<DeathLookup> {
        PdlClient(
            httpClient = resolve<HttpClient>(),
            config = resolve(),
            tokenProvider = resolve<TokenProvider>(),
        )
    }
    provide<DocumentDistributor> {
        DocumentDistributionClient(
            httpClient = resolve<HttpClient>(),
            config = resolve(),
            platformConfig = resolve(),
            tokenProvider = resolve<TokenProvider>(),
        )
    }
}
