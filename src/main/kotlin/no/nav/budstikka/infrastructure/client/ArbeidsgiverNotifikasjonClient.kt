package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.nav.budstikka.application.delivery.AltinnExternalVarsling
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRecipient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.fager.generated.NyBeskjedMutation
import no.nav.budstikka.infrastructure.client.fager.generated.NyOppgaveMutation
import no.nav.budstikka.infrastructure.client.fager.generated.type.AltinnRessursMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselAltinnressursInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselEpostInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostKontaktInfoInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.FutureTemporalInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MetadataInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NaermesteLederMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NotifikasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyBeskjedInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyOppgaveInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.SendetidspunktInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.Sendevindu
import okio.Buffer

/**
 * GraphQL anti-corruption adapter for arbeidsgiver-notifikasjon-produsent-api. Apollo generates the
 * operation and input models from fager's pinned schema, while the shared Ktor client retains
 * ownership of transport, authentication and status handling. External notifications always use
 * `LOEPENDE`, because SendingWindowGate has already enforced budstikka's window. The application
 * supplies a downstream-ready HTML body: explicit HTML is unchanged, while legacy plain text was
 * escaped by the channel handler. Fager uses an explicit Europe/Oslo hard-delete time from
 * `visibleUntil` when supplied; otherwise it schedules deletion four calendar months after receipt
 * with a `P4M` fallback, matching esyfovarsel's retention period.
 */
class ArbeidsgiverNotifikasjonClient(
    private val httpClient: HttpClient,
    private val config: ArbeidsgiverNotifikasjonConfig,
    private val tokenProvider: TokenProvider,
) : ArbeidsgiverNotificationPublisher {
    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        when (request.meldingstype) {
            ArbeidsgiverMeldingstype.BESKJED ->
                execute(request, request.toNyBeskjedMutation()) { data ->
                    data.nyBeskjed.toNotificationResponse()
                }
            ArbeidsgiverMeldingstype.OPPGAVE ->
                execute(request, request.toNyOppgaveMutation()) { data ->
                    data.nyOppgave.toNotificationResponse()
                }
        }

    private suspend fun <D : Operation.Data> execute(
        request: ArbeidsgiverNotificationRequest,
        operation: Operation<D>,
        classify: (D) -> ArbeidsgiverNotificationResponse,
    ): ArbeidsgiverNotificationResponse {
        val credential = tokenProvider.token(config.scope)
        val response =
            httpClient.post(config.url) {
                contentType(ContentType.Application.Json)
                bearerAuth(credential)
                header(X_REQUEST_ID_HEADER, request.eksternId)
                setBody(operation.requestBody())
            }
        return response.toNotificationResponse(operation, classify)
    }

    private suspend fun <D : Operation.Data> HttpResponse.toNotificationResponse(
        operation: Operation<D>,
        classify: (D) -> ArbeidsgiverNotificationResponse,
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

    private fun ArbeidsgiverNotificationRequest.toNyBeskjedMutation() =
        NyBeskjedMutation(
            input =
                NyBeskjedInput(
                    mottakere = Optional.present(listOf(recipient.toGraphqlRecipient())),
                    notifikasjon = toGraphqlNotification(),
                    metadata = toGraphqlMetadata(),
                    eksterneVarsler = recipient.toGraphqlExternalVarsler().toOptional(),
                ),
        )

    private fun ArbeidsgiverNotificationRequest.toNyOppgaveMutation() =
        NyOppgaveMutation(
            input =
                NyOppgaveInput(
                    mottakere = Optional.present(listOf(recipient.toGraphqlRecipient())),
                    notifikasjon = toGraphqlNotification(),
                    metadata = toGraphqlMetadata(),
                    eksterneVarsler = recipient.toGraphqlExternalVarsler().toOptional(),
                ),
        )

    private fun ArbeidsgiverNotificationRequest.toGraphqlNotification() =
        NotifikasjonInput(
            merkelapp = tag,
            tekst = tekst,
            lenke = lenke,
        )

    private fun ArbeidsgiverNotificationRequest.toGraphqlMetadata() =
        MetadataInput(
            virksomhetsnummer = virksomhetsnummer,
            eksternId = eksternId,
            grupperingsid = grupperingsid.toOptional(),
            hardDelete =
                Optional.present(
                    visibleUntil?.let {
                        FutureTemporalInput(
                            den = Optional.present(HARD_DELETE_DATE_TIME.format(it.toLocalDateTime(OSLO_TIME_ZONE))),
                        )
                    } ?: FutureTemporalInput(om = Optional.present(HARD_DELETE_AFTER_FOUR_MONTHS)),
                ),
        )

    private fun ArbeidsgiverNotificationRecipient.toGraphqlRecipient() =
        when (this) {
            is ArbeidsgiverNotificationRecipient.AltinnRessurs ->
                MottakerInput(
                    altinnRessurs = Optional.present(AltinnRessursMottakerInput(resource)),
                )
            is ArbeidsgiverNotificationRecipient.NarmesteLeder ->
                MottakerInput(
                    naermesteLeder =
                        Optional.present(
                            NaermesteLederMottakerInput(
                                naermesteLederFnr = narmesteLederFnr.value,
                                ansattFnr = ansattFnr.value,
                            ),
                        ),
                )
        }

    private fun ArbeidsgiverNotificationRecipient.toGraphqlExternalVarsler(): List<EksterntVarselInput>? =
        when (this) {
            is ArbeidsgiverNotificationRecipient.AltinnRessurs ->
                externalVarsling?.let { listOf(it.toGraphqlExternalVarsling(resource)) }
            is ArbeidsgiverNotificationRecipient.NarmesteLeder ->
                externalVarsling?.let { externalVarsling ->
                    externalVarsling.epostadresser.map { epostadresse ->
                        externalVarsling.toGraphqlExternalVarsling(epostadresse)
                    }
                }
        }

    private fun AltinnExternalVarsling.toGraphqlExternalVarsling(resource: String) =
        EksterntVarselInput(
            altinnressurs =
                Optional.present(
                    EksterntVarselAltinnressursInput(
                        mottaker = AltinnRessursMottakerInput(resource),
                        epostTittel = epostTittel,
                        epostHtmlBody = epostHtmlBody,
                        smsTekst = smsTekst,
                        sendetidspunkt = ongoingSendTime(),
                    ),
                ),
        )

    private fun NarmesteLederExternalVarsling.toGraphqlExternalVarsling(epostadresse: String) =
        EksterntVarselInput(
            epost =
                Optional.present(
                    EksterntVarselEpostInput(
                        mottaker =
                            EpostMottakerInput(
                                kontaktinfo =
                                    Optional.present(
                                        EpostKontaktInfoInput(epostadresse = epostadresse),
                                    ),
                            ),
                        epostTittel = epostTittel,
                        epostHtmlBody = epostHtmlBody,
                        sendetidspunkt = ongoingSendTime(),
                    ),
                ),
        )

    private fun NyBeskjedMutation.NyBeskjed.toNotificationResponse(): ArbeidsgiverNotificationResponse =
        when {
            onNyBeskjedVellykket != null ->
                ArbeidsgiverNotificationResponse.Published
            onDuplikatEksternIdOgMerkelapp != null ->
                rejected("DuplikatEksternIdOgMerkelapp")
            onUgyldigMerkelapp != null ->
                rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null ->
                rejected("UgyldigMottaker")
            onUkjentProdusent != null ->
                rejected("UkjentProdusent")
            onUkjentRolle != null ->
                rejected("UkjentRolle")
            else ->
                error("Arbeidsgiver notification API returned an unexpected NyBeskjed result")
        }

    private fun NyOppgaveMutation.NyOppgave.toNotificationResponse(): ArbeidsgiverNotificationResponse =
        when {
            onNyOppgaveVellykket != null ->
                ArbeidsgiverNotificationResponse.Published
            onDuplikatEksternIdOgMerkelapp != null ->
                rejected("DuplikatEksternIdOgMerkelapp")
            onUgyldigMerkelapp != null ->
                rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null ->
                rejected("UgyldigMottaker")
            onUkjentProdusent != null ->
                rejected("UkjentProdusent")
            onUkjentRolle != null ->
                rejected("UkjentRolle")
            onUgyldigPaaminnelseTidspunkt != null ->
                rejected("UgyldigPaaminnelseTidspunkt")
            else ->
                error("Arbeidsgiver notification API returned an unexpected NyOppgave result")
        }

    private fun rejected(resultType: String) =
        ArbeidsgiverNotificationResponse.Rejected(
            "Arbeidsgiver notification API rejected request: $resultType",
        )

    private fun <T : Any> T?.toOptional(): Optional<T?> = if (this == null) Optional.Absent else Optional.present(this)

    private fun <T : Any> List<T>?.toOptional(): Optional<List<T>> = if (this == null) Optional.Absent else Optional.present(this)

    private fun ongoingSendTime() =
        SendetidspunktInput(
            sendevindu = Optional.present(Sendevindu.LOEPENDE),
        )

    private fun <D : Operation.Data> Operation<D>.requestBody(): String =
        buildJsonString {
            this@requestBody.composeJsonRequest(this)
        }

    private companion object {
        private const val HARD_DELETE_AFTER_FOUR_MONTHS = "P4M"
        private val OSLO_TIME_ZONE = TimeZone.of("Europe/Oslo")
        private val HARD_DELETE_DATE_TIME =
            LocalDateTime.Format {
                year()
                chars("-")
                monthNumber()
                chars("-")
                day()
                chars("T")
                hour()
                chars(":")
                minute()
                chars(":")
                second()
            }

        // Fager documents X-Request-ID as an accepted correlation header in docs/gql/intro.html
        // at the pinned revision.
        private const val X_REQUEST_ID_HEADER = "X-Request-ID"
    }
}
