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
import kotlinx.serialization.SerializationException
import no.nav.budstikka.application.delivery.Personnavn
import no.nav.budstikka.application.delivery.PersonnavnLookup
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.decision.DeathLookup
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.PdlConfig
import sharedJson

/**
 * Looks up `doedsfall` and `navn` through PDL and exposes only what [DeathLookup] and
 * [PersonnavnLookup] need. Authentication uses a machine-to-machine token for [PdlConfig.scope].
 */
class PdlClient(
    private val httpClient: HttpClient,
    private val config: PdlConfig,
    private val tokenProvider: TokenProvider,
) : DeathLookup,
    PersonnavnLookup {
    override suspend fun isDead(ident: PersonIdentifier): Boolean = parseIsDead(query(personQuery(ident.value)))

    override suspend fun findName(ident: PersonIdentifier): Personnavn? = parseName(query(nameQuery(ident.value)))

    private suspend fun query(request: GraphqlRequest): String {
        val accessToken = tokenProvider.token(config.scope)
        return httpClient
            .post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(accessToken)
                header(BEHANDLINGSNUMMER_HEADER, config.behandlingsnummer)
                setBody(sharedJson.encodeToString(request))
            }.bodyAsText()
    }

    companion object {
        private const val BEHANDLINGSNUMMER_HEADER = "Behandlingsnummer"

        private const val HENT_PERSON_QUERY =
            "query(\$ident: ID!) { hentPerson(ident: \$ident) { doedsfall { doedsdato } } }"

        private const val HENT_NAVN_QUERY =
            "query(\$ident: ID!) { hentPerson(ident: \$ident) { navn { fornavn mellomnavn etternavn } } }"

        internal fun personQuery(ident: String): GraphqlRequest =
            GraphqlRequest(query = HENT_PERSON_QUERY, variables = GraphqlVariables(ident = ident))

        internal fun nameQuery(ident: String): GraphqlRequest =
            GraphqlRequest(query = HENT_NAVN_QUERY, variables = GraphqlVariables(ident = ident))

        /**
         * The current name, or `null` when PDL has none. Failures carry neither the response nor its
         * error messages, since both can contain the name.
         */
        internal fun parseName(responseBody: String): Personnavn? {
            val response =
                try {
                    sharedJson.decodeFromString<GraphqlResponse>(responseBody)
                } catch (_: SerializationException) {
                    error("PDL name response could not be read")
                }
            if (!response.errors.isNullOrEmpty()) {
                error("PDL responded with ${response.errors.size} error(s) to the name lookup")
            }
            return response.data
                ?.fetchPerson
                ?.names
                ?.firstOrNull()
                ?.let { Personnavn(fornavn = it.fornavn, mellomnavn = it.mellomnavn, etternavn = it.etternavn) }
        }

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
    @SerialName("navn")
    val names: List<Name>? = null,
)

@Serializable
internal data class Name(
    val fornavn: String,
    val mellomnavn: String? = null,
    val etternavn: String,
) {
    override fun toString(): String = "Name()"
}

@Serializable
internal data class Death(
    @SerialName("doedsdato")
    val date: String? = null,
)
