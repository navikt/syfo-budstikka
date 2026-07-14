package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.server.plugins.di.DependencyRegistry
import io.ktor.server.plugins.di.resolve
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider

/**
 * DI for død-oppslaget: registrerer [DeathLookup] med den ekte [PdlClient]-adapteren mot pdl-api.
 * Gjenbruker den delte utgående [HttpClient] og [TokenProvider] (Texas) fra `authModule` – ingen
 * egen HttpClient her (duplikat-registrering ville kastet i prod).
 *
 * I test/lokalt løp overstyres [DeathLookup] mot [no.nav.budstikka.fakes.FakeDeathLookup] via
 * wiring-sømmen (`overrides`), så [PdlClient], [TokenProvider] og Texas-sidecar aldri røres der.
 */
fun DependencyRegistry.clientModule() {
    provide<DeathLookup> {
        PdlClient(
            httpClient = resolve<HttpClient>(),
            config = resolve(),
            tokenProvider = resolve<TokenProvider>(),
        )
    }
}
