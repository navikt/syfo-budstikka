package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import no.nav.budstikka.application.port.NarmesteLederLookup
import no.nav.budstikka.application.port.NarmesteLederRelasjon
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.NarmesteLederConfig
import sharedJson

class NarmesteLederClient(
    private val httpClient: HttpClient,
    private val config: NarmesteLederConfig,
    private val tokenProvider: TokenProvider,
) : NarmesteLederLookup {
    override suspend fun findActive(
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
    ): NarmesteLederRelasjon? {
        val accessToken = tokenProvider.token(config.scope)
        val response =
            httpClient.get("${config.url}/internal/narmesteleder") {
                parameter("orgnummer", orgnummer.value)
                accept(ContentType.Application.Json)
                bearerAuth(accessToken)
                header(SYKMELDT_FNR_HEADER, sykmeldt.value)
            }
        return parseActive(response.status, response.bodyAsText())
    }

    companion object {
        private const val SYKMELDT_FNR_HEADER = "Sykmeldt-Fnr"

        internal fun parseActive(
            status: HttpStatusCode,
            responseBody: String,
        ): NarmesteLederRelasjon? {
            check(status.isSuccess()) { "Narmeste leder register responded with status ${status.value}" }
            val response =
                try {
                    sharedJson.decodeFromString<NarmesteLederResponse>(responseBody)
                } catch (_: SerializationException) {
                    // Do not retain the cause: its message can echo the body, including fnr and email addresses.
                    error("Narmeste leder register returned an invalid response with status ${status.value}")
                }
            return response.narmesteLeder?.let { relation ->
                NarmesteLederRelasjon(
                    narmesteLederFnr = PersonIdentifier(relation.fnr),
                    epostadresser = relation.epostadresser,
                )
            }
        }
    }
}

@Serializable
internal data class NarmesteLederResponse(
    val narmesteLeder: NarmesteLederResponseRelation? = null,
)

@Serializable
internal data class NarmesteLederResponseRelation(
    val fnr: String,
    val epostadresser: List<String>,
)
