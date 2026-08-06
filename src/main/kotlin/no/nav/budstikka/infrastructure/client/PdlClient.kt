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
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.foundation.DeathLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.PdlConfig
import sharedJson

/**
 * Looks up `doedsfall` through PDL and exposes only the result needed by [DeathLookup]. Authentication
 * uses a machine-to-machine token for [PdlConfig.scope].
 */
class PdlClient(
    private val httpClient: HttpClient,
    private val config: PdlConfig,
    private val tokenProvider: TokenProvider,
) : DeathLookup {
    override suspend fun isDead(ident: PersonIdentifier): Boolean {
        val accessToken = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(accessToken)
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
         * Pure interpretation of the PDL response (testable without HTTP): dead = at least one
         * `doedsfall` entry. Throws on GraphQL `errors`, so the shell handles it as a transient or
         * permanent failure rather than silently interpreting it as “not dead”.
         */
        internal fun parseIsDead(responseBody: String): Boolean {
            val response = sharedJson.decodeFromString<GraphqlResponse>(responseBody)
            if (!response.errors.isNullOrEmpty()) {
                error("PDL responded with errors: ${response.errors.joinToString { it.message }}")
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
