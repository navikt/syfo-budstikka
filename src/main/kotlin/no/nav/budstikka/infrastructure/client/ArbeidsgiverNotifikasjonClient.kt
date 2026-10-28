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
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.application.delivery.KalenderavtaleCreateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateRequest
import no.nav.budstikka.application.delivery.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.fager.generated.HentNotifikasjonQuery
import no.nav.budstikka.infrastructure.client.fager.generated.HentSakQuery
import no.nav.budstikka.infrastructure.client.fager.generated.NyBeskjedMutation
import no.nav.budstikka.infrastructure.client.fager.generated.NyKalenderavtaleMutation
import no.nav.budstikka.infrastructure.client.fager.generated.NyOppgaveMutation
import no.nav.budstikka.infrastructure.client.fager.generated.NySakMutation
import no.nav.budstikka.infrastructure.client.fager.generated.NyStatusSakByGrupperingsidMutation
import no.nav.budstikka.infrastructure.client.fager.generated.OppdaterKalenderavtaleByEksternIdMutation
import no.nav.budstikka.infrastructure.client.fager.generated.type.AltinnRessursMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselAltinnressursInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselEpostInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostKontaktInfoInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.FutureTemporalInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.HardDeleteUpdateInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.KalenderavtaleTilstand
import no.nav.budstikka.infrastructure.client.fager.generated.type.LokasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MetadataInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.MottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NaermesteLederMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NotifikasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyBeskjedInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyOppgaveInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyTidStrategi
import no.nav.budstikka.infrastructure.client.fager.generated.type.SaksStatus
import no.nav.budstikka.infrastructure.client.fager.generated.type.SendetidspunktInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.Sendevindu
import okio.Buffer
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * GraphQL anti-corruption adapter for arbeidsgiver-notifikasjon-produsent-api. Apollo generates the
 * operation and input models from fager's pinned schema, while the shared Ktor client retains
 * ownership of transport, authentication and status handling. External notifications always use
 * `LOEPENDE`, because SendingWindowGate has already enforced budstikka's window. The application
 * supplies a downstream-ready HTML body: explicit HTML is unchanged, while legacy plain text was
 * escaped by the channel handler. Fager uses an explicit Europe/Oslo hard-delete time from
 * `visibleUntil` when supplied; otherwise it schedules deletion four calendar months after receipt
 * with a `P4M` fallback, matching esyfovarsel's retention period. Cases and calendar events use the
 * producer's keys (grupperingsid and eksternId) and the `ByGrupperingsid`/`ByEksternId` mutations, so
 * no fager id is stored.
 */
class ArbeidsgiverNotifikasjonClient(
    private val httpClient: HttpClient,
    private val config: ArbeidsgiverNotifikasjonConfig,
    private val tokenProvider: TokenProvider,
) : ArbeidsgiverNotificationPublisher,
    ArbeidsgiversakPublisher {
    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        when (request.meldingstype) {
            ArbeidsgiverMeldingstype.BESKJED ->
                execute(request.eksternId, request.toNyBeskjedMutation()) { data ->
                    data.nyBeskjed.toNotificationResponse()
                }
            ArbeidsgiverMeldingstype.OPPGAVE ->
                execute(request.eksternId, request.toNyOppgaveMutation()) { data ->
                    data.nyOppgave.toNotificationResponse()
                }
        }

    override suspend fun createCase(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        execute(request.correlationId, request.toNySakMutation()) { data -> data.nySak.toCaseResponse(request) }

    override suspend fun updateCaseStatus(request: ArbeidsgiversakStatusRequest): ArbeidsgiverNotificationResponse =
        execute(request.idempotencyKey, request.toNyStatusSakMutation()) { data ->
            data.nyStatusSakByGrupperingsid.toCaseStatusResponse()
        }

    override suspend fun createCalendarEvent(request: KalenderavtaleCreateRequest): ArbeidsgiverNotificationResponse =
        execute(request.correlationId, request.toNyKalenderavtaleMutation()) { data ->
            data.nyKalenderavtale.toCalendarCreateResponse(request)
        }

    override suspend fun updateCalendarEvent(request: KalenderavtaleUpdateRequest): ArbeidsgiverNotificationResponse =
        execute(request.idempotencyKey, request.toOppdaterKalenderavtaleMutation()) { data ->
            data.oppdaterKalenderavtaleByEksternId.toCalendarUpdateResponse()
        }

    private suspend fun <D : Operation.Data> execute(
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
                Optional.present(visibleUntil.toHardDeleteInput()),
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
                invalidRecipient("UgyldigMottaker")
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
                invalidRecipient("UgyldigMottaker")
            onUkjentProdusent != null ->
                rejected("UkjentProdusent")
            onUkjentRolle != null ->
                rejected("UkjentRolle")
            onUgyldigPaaminnelseTidspunkt != null ->
                rejected("UgyldigPaaminnelseTidspunkt")
            else ->
                error("Arbeidsgiver notification API returned an unexpected NyOppgave result")
        }

    private fun ArbeidsgiversakCreateRequest.toNySakMutation() =
        NySakMutation(
            grupperingsid = grupperingsid,
            merkelapp = tag,
            virksomhetsnummer = virksomhetsnummer,
            mottakere = listOf(recipient.toGraphqlRecipient()),
            tittel = tittel,
            lenke = lenke.toOptional(),
            initiellStatus = initiellStatus.toGraphql(),
            hardDelete = Optional.present(hardDeleteAt.toHardDeleteInput()),
        )

    private fun ArbeidsgiversakStatusRequest.toNyStatusSakMutation() =
        NyStatusSakByGrupperingsidMutation(
            idempotencyKey = Optional.present(idempotencyKey),
            grupperingsid = grupperingsid,
            merkelapp = tag,
            nyStatus = status.toGraphql(),
            overstyrStatustekstMed = statustekst.toOptional(),
            hardDelete =
                hardDeleteAt
                    ?.let {
                        HardDeleteUpdateInput(nyTid = it.toHardDeleteInput(), strategi = NyTidStrategi.OVERSKRIV)
                    }.toOptional(),
        )

    private fun KalenderavtaleCreateRequest.toNyKalenderavtaleMutation() =
        NyKalenderavtaleMutation(
            virksomhetsnummer = virksomhetsnummer,
            grupperingsid = grupperingsid,
            merkelapp = tag,
            eksternId = eksternId,
            tekst = tekst,
            lenke = lenke,
            mottakere = listOf(recipient.toGraphqlRecipient()),
            startTidspunkt = startAt.toOsloOffsetDateTime(),
            sluttTidspunkt = endAt?.toOsloOffsetDateTime().toOptional(),
            lokasjon =
                lokasjon
                    ?.let { LokasjonInput(adresse = it.adresse, postnummer = it.postnummer, poststed = it.poststed) }
                    .toOptional(),
            erDigitalt = Optional.present(erDigitalt),
            tilstand = Optional.present(tilstand.toGraphql()),
            eksterneVarsler = recipient.externalVarsling?.toCalendarEmails().orEmpty(),
        )

    private fun KalenderavtaleUpdateRequest.toOppdaterKalenderavtaleMutation() =
        OppdaterKalenderavtaleByEksternIdMutation(
            merkelapp = tag,
            eksternId = eksternId,
            nyTilstand = nyTilstand?.toGraphql().toOptional(),
            nyTekst = nyTekst.toOptional(),
            idempotencyKey = Optional.present(idempotencyKey),
            eksterneVarsler = externalVarsling?.toCalendarEmails().orEmpty(),
        )

    /**
     * Calendar emails use Fager's own contact-centre window instead of budstikka's: the event itself
     * must exist at once so later updates can address it, and Fager drops unsent emails when the
     * event is cancelled.
     */
    private fun NarmesteLederExternalVarsling.toCalendarEmails(): List<EksterntVarselInput> =
        epostadresser.map { epostadresse ->
            EksterntVarselInput(
                epost =
                    Optional.present(
                        EksterntVarselEpostInput(
                            mottaker =
                                EpostMottakerInput(
                                    kontaktinfo = Optional.present(EpostKontaktInfoInput(epostadresse = epostadresse)),
                                ),
                            epostTittel = epostTittel,
                            epostHtmlBody = epostHtmlBody,
                            sendetidspunkt =
                                SendetidspunktInput(sendevindu = Optional.present(Sendevindu.NKS_AAPNINGSTID)),
                        ),
                    ),
            )
        }

    private suspend fun NySakMutation.NySak.toCaseResponse(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        when {
            onNySakVellykket != null -> ArbeidsgiverNotificationResponse.Published
            // Fager answers an identical replay with success, so a duplicate means the key exists with
            // other content: our own earlier attempt after a leader change, or a caseId reused by
            // mistake. Only the first counts as created, so the existing case is checked.
            onDuplikatGrupperingsid != null ->
                verifyExistingCase(request, onDuplikatGrupperingsid.idTilEksisterende)
            onDuplikatGrupperingsidEtterDelete != null -> rejected("DuplikatGrupperingsidEtterDelete")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null -> invalidRecipient("UgyldigMottaker")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            onUkjentRolle != null -> rejected("UkjentRolle")
            else -> error("Arbeidsgiver notification API returned an unexpected NySak result")
        }

    private fun NyStatusSakByGrupperingsidMutation.NyStatusSakByGrupperingsid.toCaseStatusResponse() =
        when {
            onNyStatusSakVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onSakFinnesIkke != null -> notYetAvailable("SakFinnesIkke")
            onKonflikt != null -> rejected("Konflikt")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected NyStatusSak result")
        }

    private suspend fun NyKalenderavtaleMutation.NyKalenderavtale.toCalendarCreateResponse(request: KalenderavtaleCreateRequest) =
        when {
            onNyKalenderavtaleVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onSakFinnesIkke != null -> notYetAvailable("SakFinnesIkke")
            // A duplicate means the eksternId exists with other content: our own earlier attempt that
            // has since been updated, or a reference reused by mistake. The existing event is checked.
            onDuplikatEksternIdOgMerkelapp != null ->
                verifyExistingCalendarEvent(request, onDuplikatEksternIdOgMerkelapp.idTilEksisterende)
            onUgyldigKalenderavtale != null -> rejected("UgyldigKalenderavtale")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null -> invalidRecipient("UgyldigMottaker")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected NyKalenderavtale result")
        }

    private fun OppdaterKalenderavtaleByEksternIdMutation.OppdaterKalenderavtaleByEksternId.toCalendarUpdateResponse() =
        when {
            onOppdaterKalenderavtaleVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onNotifikasjonFinnesIkke != null -> notYetAvailable("NotifikasjonFinnesIkke")
            onKonflikt != null -> rejected("Konflikt")
            onUgyldigKalenderavtale != null -> rejected("UgyldigKalenderavtale")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected OppdaterKalenderavtale result")
        }

    /** The existing case is ours when it belongs to the same organisation; grupperingsid and tag already match. */
    private suspend fun verifyExistingCase(
        request: ArbeidsgiversakCreateRequest,
        existingId: String,
    ): ArbeidsgiverNotificationResponse =
        execute(request.correlationId, HentSakQuery(id = existingId)) { data ->
            val existing = data.hentSak.onHentetSak?.sak
            when {
                existing == null -> rejected("DuplikatGrupperingsid that could not be verified")
                existing.virksomhetsnummer == request.virksomhetsnummer -> ArbeidsgiverNotificationResponse.Published
                else -> rejected("DuplikatGrupperingsid in another organisation")
            }
        }

    /** The existing notification is ours when it is a calendar event in the same case. */
    private suspend fun verifyExistingCalendarEvent(
        request: KalenderavtaleCreateRequest,
        existingId: String,
    ): ArbeidsgiverNotificationResponse =
        execute(request.correlationId, HentNotifikasjonQuery(id = existingId)) { data ->
            val existing = data.hentNotifikasjon.onHentetNotifikasjon?.notifikasjon
            when {
                existing == null -> rejected("DuplikatEksternIdOgMerkelapp that could not be verified")
                existing.onKalenderavtale?.metadata?.grupperingsid == request.grupperingsid ->
                    ArbeidsgiverNotificationResponse.Published
                else -> rejected("DuplikatEksternIdOgMerkelapp for another case or notification type")
            }
        }

    private fun rejected(resultType: String) =
        ArbeidsgiverNotificationResponse.Rejected(
            "Arbeidsgiver notification API rejected request: $resultType",
        )

    private fun invalidRecipient(resultType: String) =
        ArbeidsgiverNotificationResponse.InvalidRecipient(
            "Arbeidsgiver notification API rejected request: $resultType",
        )

    private fun notYetAvailable(resultType: String) =
        ArbeidsgiverNotificationResponse.NotYetAvailable(
            "Arbeidsgiver notification API has not found the target yet: $resultType",
        )

    private fun Instant?.toHardDeleteInput(): FutureTemporalInput =
        this?.let {
            FutureTemporalInput(den = Optional.present(LocalDateTime.Formats.ISO.format(it.toLocalDateTime(OSLO_TIME_ZONE))))
        } ?: FutureTemporalInput(om = Optional.present(HARD_DELETE_AFTER_FOUR_MONTHS))

    private fun Instant.toOsloOffsetDateTime(): String =
        toJavaInstant().atZone(OSLO_ZONE_ID).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun Saksstatus.toGraphql(): SaksStatus =
        when (this) {
            Saksstatus.MOTTATT -> SaksStatus.MOTTATT
            Saksstatus.UNDER_BEHANDLING -> SaksStatus.UNDER_BEHANDLING
            Saksstatus.FERDIG -> SaksStatus.FERDIG
        }

    private fun Kalenderavtaletilstand.toGraphql(): KalenderavtaleTilstand =
        when (this) {
            Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER -> KalenderavtaleTilstand.VENTER_SVAR_FRA_ARBEIDSGIVER
            Kalenderavtaletilstand.ARBEIDSGIVER_VIL_AVLYSE -> KalenderavtaleTilstand.ARBEIDSGIVER_VIL_AVLYSE
            Kalenderavtaletilstand.ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED ->
                KalenderavtaleTilstand.ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED
            Kalenderavtaletilstand.ARBEIDSGIVER_HAR_GODTATT -> KalenderavtaleTilstand.ARBEIDSGIVER_HAR_GODTATT
            Kalenderavtaletilstand.AVLYST -> KalenderavtaleTilstand.AVLYST
            Kalenderavtaletilstand.AVHOLDT -> KalenderavtaleTilstand.AVHOLDT
        }

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
        private val OSLO_ZONE_ID = ZoneId.of("Europe/Oslo")

        // Fager documents X-Request-ID as an accepted correlation header in docs/gql/intro.html
        // at the pinned revision.
        private const val X_REQUEST_ID_HEADER = "X-Request-ID"
    }
}
