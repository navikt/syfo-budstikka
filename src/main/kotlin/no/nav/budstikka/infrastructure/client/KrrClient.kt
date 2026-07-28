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
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.ReservationLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.KrrConfig
import sharedJson

/**
 * B22 anti-corruption adapter for ReservationGate (ADR 0009): looks up Recipient contact and
 * Reservasjon status in digdir-krr-proxy and translates it to [ReservationLookup]. KRR
 * `kanVarsles == false` means no `ekstern varsling`, either because of Reservasjon or because there
 * is no verified contact information.
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

        internal fun parseIsReserved(
            status: HttpStatusCode,
            responseBody: String,
        ): Boolean {
            check(status.isSuccess()) { "KRR responded with status ${status.value}" }
            val person =
                try {
                    sharedJson.decodeFromString<KrrPerson>(responseBody)
                } catch (_: SerializationException) {
                    // Do not retain the cause: its message can echo the body, including fnr, in a stacktrace.
                    error("KRR returned an invalid response with status ${status.value}")
                }
            return !person.kanVarsles
        }
    }
}

@Serializable
internal data class KrrPerson(
    val kanVarsles: Boolean,
)
