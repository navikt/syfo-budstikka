package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.ReservationLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.KrrConfig

/**
 * Ekte KRR-adapter (B22 anti-corruption, ADR 0009) for reservasjonsgaten: slår opp mottakerens
 * kontakt-/reservasjonsstatus i digdir-krr-proxy og oversetter til [ReservationLookup]-porten.
 * «Reservert» = KRR `kanVarsles == false` (kan ikke varsles digitalt – reservert ELLER mangler
 * verifisert kontaktkanal).
 *
 * Auth: maskin-til-maskin bearer-token fra [tokenProvider] (Texas, Azure AD `client_credentials`)
 * for KRR-scopet ([KrrConfig.scope]) – budstikka har ingen brukerkontekst, så TokenX/OBO er ikke
 * anvendbart (ADR 0009). Gjenbruker den delte utgående [httpClient].
 *
 * PII-disiplin (B46): fnr sendes til KRR i `Nav-Personident`-headeren, men aldri til logg;
 * KRR-feilresponser kan bære fnr i body, så feil kastes med KUN statuskode – aldri body/token.
 */
class KrrClient(
    private val httpClient: HttpClient,
    private val config: KrrConfig,
    private val tokenProvider: TokenProvider,
) : ReservationLookup {
    override suspend fun isReserved(ident: PersonIdentifier): Boolean {
        val token = tokenProvider.token(config.scope)
        val response =
            httpClient.get(config.url) {
                accept(ContentType.Application.Json)
                bearerAuth(token)
                header(NAV_PERSONIDENT_HEADER, ident.value)
            }
        return parseIsReserved(response.status, response.bodyAsText())
    }

    companion object {
        private const val NAV_PERSONIDENT_HEADER = "Nav-Personident"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Ren tolkning av KRR-svaret (testbar uten HTTP): reservert = `kanVarsles == false`. Kaster
         * på ikke-2xx og på uparsebar/uventet body slik at skallet håndterer det som en
         * (transient/permanent) feil – aldri stille tolket som «ikke reservert». Feilmeldingen bærer
         * kun statuskode, aldri body (som kan inneholde fnr).
         */
        internal fun parseIsReserved(
            status: HttpStatusCode,
            responseBody: String,
        ): Boolean {
            check(status.isSuccess()) { "KRR svarte med status ${status.value}" }
            val person =
                try {
                    json.decodeFromString<KrrPerson>(responseBody)
                } catch (_: SerializationException) {
                    // Ikke behold cause: dens melding kan gjengi body (med fnr) i en stacktrace.
                    error("KRR returnerte et ugyldig svar med status ${status.value}")
                }
            return !person.kanVarsles
        }
    }
}

@Serializable
internal data class KrrPerson(
    val kanVarsles: Boolean,
)
