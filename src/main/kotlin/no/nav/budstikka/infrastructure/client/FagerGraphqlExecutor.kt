package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.composeJsonRequest
import com.apollographql.apollo.api.json.buildJsonString
import com.apollographql.apollo.api.json.jsonReader
import com.apollographql.apollo.api.parseResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import okio.Buffer

/**
 * Transport shared by the arbeidsgiver-notifikasjon-produsent-api clients. It authenticates and posts one
 * Apollo operation with a correlation id, then maps HTTP and GraphQL failures. The caller classifies the
 * business result.
 */
internal class FagerGraphqlExecutor(
    private val httpClient: HttpClient,
    private val config: ArbeidsgiverNotifikasjonConfig,
    private val tokenProvider: TokenProvider,
) {
    suspend fun <D : Operation.Data> execute(
        correlationId: String,
        operation: Operation<D>,
        classify: suspend (D) -> ArbeidsgiverNotificationResponse,
    ): ArbeidsgiverNotificationResponse {
        val credential = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(credential)
                header(X_REQUEST_ID_HEADER, correlationId)
                setBody(operation.requestBody())
            }
        return response.toNotificationResponse(operation, classify)
    }

    private suspend fun <D : Operation.Data> HttpResponse.toNotificationResponse(
        operation: Operation<D>,
        classify: suspend (D) -> ArbeidsgiverNotificationResponse,
    ): ArbeidsgiverNotificationResponse {
        if (status == HttpStatusCode.BadRequest) {
            return ArbeidsgiverNotificationResponse.Rejected(
                "Arbeidsgiver notification API rejected request with status ${status.value}",
            )
        }
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden || status.value in 500..599) {
            error("Arbeidsgiver notification API failed with status ${status.value}")
        }
        // A 404 is a missing route or ingress/configuration fault, not a GraphQL business rejection.
        if (!status.isSuccess()) error("Arbeidsgiver notification API returned unexpected status ${status.value}")

        val responseBody = bodyAsText()
        val payload =
            Buffer()
                .writeUtf8(responseBody)
                .jsonReader()
                .use { jsonReader ->
                    operation.parseResponse(jsonReader)
                }
        if (payload.exception != null) {
            error("Arbeidsgiver notification API returned an invalid response")
        }
        if (!payload.errors.isNullOrEmpty()) error("Arbeidsgiver notification API returned GraphQL errors")
        return classify(payload.data ?: error("Arbeidsgiver notification API returned no result"))
    }

    private fun <D : Operation.Data> Operation<D>.requestBody(): String =
        buildJsonString {
            this@requestBody.composeJsonRequest(this)
        }

    private companion object {
        // Fager documents X-Request-ID as an accepted correlation header in docs/gql/intro.html
        // at the pinned revision.
        private const val X_REQUEST_ID_HEADER = "X-Request-ID"
    }
}
