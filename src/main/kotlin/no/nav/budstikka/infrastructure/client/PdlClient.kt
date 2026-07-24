package no.nav.budstikka.infrastructure.client

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.PdlConfig
import sharedJson

/**
 * Ekte PDL-adapter (B22 anti-corruption) for død-gaten: stiller GraphQL-spørringen `hentPerson`
 * og avgjør død ut fra `doedsfall`-lista. Domeneblind – oversetter kun PDL-svaret til
 * [DeathLookup]-porten.
 *
 * Auth: henter et maskin-til-maskin bearer-token fra [tokenProvider] (Texas, #48) for PDL-scopet
 * ([PdlConfig.scope]) og gjenbruker den delte utgående [httpClient]. Token er en hemmelighet og
 * logges aldri.
 */
class PdlClient(
    private val httpClient: HttpClient,
    private val config: PdlConfig,
    private val tokenProvider: TokenProvider,
) : DeathLookup {
    override suspend fun isDead(ident: PersonIdentifier): Boolean {
        val token = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                header(BEHANDLINGSNUMMER_HEADER, config.behandlingsnummer)
                setBody(sharedJson.encodeToString(personQuery(ident.value)))
            }
        return parseIsDead(response.bodyAsText())
    }

    companion object {
        private const val BEHANDLINGSNUMMER_HEADER = "Behandlingsnummer"

        private const val HENT_PERSON_QUERY =
            "query(\$ident: ID!) { hentPerson(ident: \$ident) { doedsfall { doedsdato } } }"

        internal fun personQuery(ident: String): GraphqlRequest =
            GraphqlRequest(query = HENT_PERSON_QUERY, variables = GraphqlVariables(ident = ident))

        /**
         * Ren tolkning av PDL-svaret (testbar uten HTTP): død = minst én `doedsfall`-oppføring.
         * Kaster på GraphQL-`errors` slik at det håndteres som en (transient/permanent) feil av
         * skallet, aldri stille tolkes som «ikke død».
         */
        internal fun parseIsDead(responseBody: String): Boolean {
            val response = sharedJson.decodeFromString<GraphqlResponse>(responseBody)
            if (!response.errors.isNullOrEmpty()) {
                error("PDL svarte med feil: ${response.errors.joinToString { it.message }}")
            }
            val deaths =
                response.data
                    ?.fetchPerson
                    ?.deaths
                    .orEmpty()
            return deaths.isNotEmpty()
        }
    }
}

@Serializable
internal data class GraphqlRequest(
    val query: String,
    val variables: GraphqlVariables,
)

@Serializable
internal data class GraphqlVariables(
    val ident: String,
)

@Serializable
internal data class GraphqlResponse(
    val data: PdlData? = null,
    val errors: List<GraphqlError>? = null,
)

@Serializable
internal data class GraphqlError(
    val message: String = "",
)

@Serializable
internal data class PdlData(
    @SerialName("hentPerson")
    val fetchPerson: FetchPerson? = null,
)

@Serializable
internal data class FetchPerson(
    @SerialName("doedsfall")
    val deaths: List<Death>? = null,
)

@Serializable
internal data class Death(
    @SerialName("doedsdato")
    val date: String? = null,
)
