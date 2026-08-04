package no.nav.budstikka.infrastructure.client

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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import no.nav.budstikka.application.port.ArbeidsgiverExternalVarsling
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgiverMeldingstype
import no.nav.budstikka.domain.dispatch.Tag
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig

/**
 * GraphQL anti-corruption adapter for arbeidsgiver-notifikasjon-produsent-api. It always uses
 * `LOEPENDE`, because SendingWindowGate has already enforced budstikka's window. Altinn 3 ignores
 * the contract's external-notification channels and uses its platform-defined delivery preference.
 * Email body is escaped from consumer-provided plain text before it becomes downstream HTML.
 */
class ArbeidsgiverNotifikasjonClient(
    private val httpClient: HttpClient,
    private val config: ArbeidsgiverNotifikasjonConfig,
    private val tokenProvider: TokenProvider,
) : ArbeidsgiverNotificationPublisher {
    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        val token = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(token)
                header(NAV_CALL_ID_HEADER, request.eksternId)
                setBody(json.encodeToString(request.toGraphqlRequest()))
            }
        return response.toNotificationResponse()
    }

    private suspend fun HttpResponse.toNotificationResponse(): ArbeidsgiverNotificationResponse {
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
            try {
                json.decodeFromString<NotificationGraphqlResponse>(responseBody)
            } catch (_: SerializationException) {
                error("Arbeidsgiver notification API returned an invalid response")
            }
        if (!payload.errors.isNullOrEmpty()) error("Arbeidsgiver notification API returned GraphQL errors")
        val result = payload.data?.result ?: error("Arbeidsgiver notification API returned no result")
        return when (result.typeName) {
            "NyBeskjedVellykket", "NyOppgaveVellykket", "DuplikatEksternIdOgMerkelapp" ->
                ArbeidsgiverNotificationResponse.Published
            "UgyldigMerkelapp", "UgyldigMottaker", "UkjentProdusent", "UkjentRolle", "UgyldigPaaminnelseTidspunkt" ->
                ArbeidsgiverNotificationResponse.Rejected("Arbeidsgiver notification API rejected request: ${result.typeName}")
            else ->
                ArbeidsgiverNotificationResponse.Rejected(
                    "Arbeidsgiver notification API returned unexpected result: ${result.typeName}",
                )
        }
    }

    private fun ArbeidsgiverNotificationRequest.toGraphqlRequest(): NotificationGraphqlRequest =
        NotificationGraphqlRequest(
            query =
                when (meldingstype) {
                    ArbeidsgiverMeldingstype.BESKJED -> NY_BESKJED_MUTATION
                    ArbeidsgiverMeldingstype.OPPGAVE -> NY_OPPGAVE_MUTATION
                },
            variables =
                when (meldingstype) {
                    ArbeidsgiverMeldingstype.BESKJED -> NotificationGraphqlVariables(nyBeskjed = toGraphqlInput())
                    ArbeidsgiverMeldingstype.OPPGAVE -> NotificationGraphqlVariables(nyOppgave = toGraphqlInput())
                },
        )

    private fun ArbeidsgiverNotificationRequest.toGraphqlInput() =
        NotificationInput(
            mottakere = listOf(NotificationRecipient(altinnRessurs = AltinnRessursInput(altinnRessurs.toWireValue()))),
            notifikasjon = NotificationContent(tag.toWireValue(), tekst, lenke),
            metadata = NotificationMetadata(virksomhetsnummer, eksternId, grupperingsid),
            eksterneVarsler = externalVarsling?.let { listOf(it.toGraphqlExternalVarsling(altinnRessurs)) },
        )

    private fun ArbeidsgiverExternalVarsling.toGraphqlExternalVarsling(resource: AltinnResourceId) =
        ExternalNotificationInput(
            altinnressurs =
                AltinnResourceExternalNotification(
                    mottaker = AltinnRessursInput(resource.toWireValue()),
                    epostTittel = epostTittel,
                    epostHtmlBody = epostTekst.toEscapedHtml(),
                    smsTekst = smsTekst,
                    sendetidspunkt = SendetidspunktInput(sendevindu = "LOEPENDE"),
                ),
        )

    private fun Tag.toWireValue(): String =
        when (this) {
            Tag.DIALOGMOETE -> "Dialogmøte"
            Tag.OPPFOELGING -> "Oppfølging"
        }

    private fun AltinnResourceId.toWireValue(): String =
        when (this) {
            AltinnResourceId.DIALOGMOETE -> "nav_syfo_dialogmote"
        }

    private fun String.toEscapedHtml(): String =
        buildString {
            this@toEscapedHtml
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .forEach { character ->
                    append(
                        when (character) {
                            '&' -> "&amp;"
                            '<' -> "&lt;"
                            '>' -> "&gt;"
                            '"' -> "&quot;"
                            '\'' -> "&#39;"
                            '\n' -> "<br>"
                            else -> character
                        },
                    )
                }
        }

    private companion object {
        private const val NAV_CALL_ID_HEADER = "Nav-Call-Id"
        private const val NY_BESKJED_MUTATION =
            "mutation nyBeskjed(\$nyBeskjed: NyBeskjedInput!) { nyBeskjed(nyBeskjed: \$nyBeskjed) { __typename } }"
        private const val NY_OPPGAVE_MUTATION =
            "mutation nyOppgave(\$nyOppgave: NyOppgaveInput!) { nyOppgave(nyOppgave: \$nyOppgave) { __typename } }"
        private val json =
            Json {
                encodeDefaults = false
                ignoreUnknownKeys = true
            }
    }
}

@Serializable
private data class NotificationGraphqlRequest(
    val query: String,
    val variables: NotificationGraphqlVariables,
)

@Serializable
private data class NotificationGraphqlVariables(
    val nyBeskjed: NotificationInput? = null,
    val nyOppgave: NotificationInput? = null,
)

@Serializable
private data class NotificationInput(
    val mottakere: List<NotificationRecipient>,
    val notifikasjon: NotificationContent,
    val metadata: NotificationMetadata,
    val eksterneVarsler: List<ExternalNotificationInput>? = null,
)

@Serializable
private data class NotificationRecipient(
    @SerialName("altinnRessurs") val altinnRessurs: AltinnRessursInput,
)

@Serializable
private data class AltinnRessursInput(
    val ressursId: String,
)

@Serializable
private data class NotificationContent(
    val merkelapp: String,
    val tekst: String,
    val lenke: String,
)

@Serializable
private data class NotificationMetadata(
    val virksomhetsnummer: String,
    val eksternId: String,
    val grupperingsid: String? = null,
)

@Serializable
private data class ExternalNotificationInput(
    val altinnressurs: AltinnResourceExternalNotification,
)

@Serializable
private data class AltinnResourceExternalNotification(
    val mottaker: AltinnRessursInput,
    val epostTittel: String,
    val epostHtmlBody: String,
    val smsTekst: String,
    val sendetidspunkt: SendetidspunktInput,
)

@Serializable
private data class SendetidspunktInput(
    val sendevindu: String,
)

@Serializable
private data class NotificationGraphqlResponse(
    val data: NotificationData? = null,
    val errors: List<GraphqlError>? = null,
)

@Serializable
private data class NotificationData(
    val nyBeskjed: NotificationResult? = null,
    val nyOppgave: NotificationResult? = null,
) {
    val result: NotificationResult? get() = nyBeskjed ?: nyOppgave
}

@Serializable
private data class NotificationResult(
    @SerialName("__typename") val typeName: String,
)
