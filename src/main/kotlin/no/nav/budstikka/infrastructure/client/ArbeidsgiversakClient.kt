package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Optional
import io.ktor.client.HttpClient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakExtendRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.application.delivery.KalenderavtaleCreateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateRequest
import no.nav.budstikka.application.delivery.NarmesteLederExternalVarsling
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.fager.generated.CreateKalenderavtaleMutation
import no.nav.budstikka.infrastructure.client.fager.generated.CreateSakMutation
import no.nav.budstikka.infrastructure.client.fager.generated.ExtendSakMutation
import no.nav.budstikka.infrastructure.client.fager.generated.FetchNotifikasjonQuery
import no.nav.budstikka.infrastructure.client.fager.generated.FetchSakQuery
import no.nav.budstikka.infrastructure.client.fager.generated.SetSaksstatusMutation
import no.nav.budstikka.infrastructure.client.fager.generated.UpdateKalenderavtaleMutation
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselEpostInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EksterntVarselInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostKontaktInfoInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.EpostMottakerInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.HardDeleteUpdateInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.KalenderavtaleTilstand
import no.nav.budstikka.infrastructure.client.fager.generated.type.LokasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyTidStrategi
import no.nav.budstikka.infrastructure.client.fager.generated.type.SaksStatus
import no.nav.budstikka.infrastructure.client.fager.generated.type.SendetidspunktInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.Sendevindu
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/**
 * GraphQL anti-corruption adapter for the sak mutations of arbeidsgiver-notifikasjon-produsent-api,
 * which budstikka owns through [ArbeidsgiversakPublisher]. Saker and kalenderavtaler are addressed by
 * grupperingsid and eksternId through the `ByGrupperingsid`/`ByEksternId` mutations, so no fager id is
 * stored. Transport, authentication and status handling are shared with
 * [ArbeidsgiverNotifikasjonClient] through [FagerGraphqlExecutor].
 */
class ArbeidsgiversakClient(
    httpClient: HttpClient,
    config: ArbeidsgiverNotifikasjonConfig,
    tokenProvider: TokenProvider,
) : ArbeidsgiversakPublisher {
    private val executor = FagerGraphqlExecutor(httpClient, config, tokenProvider)

    override suspend fun createSak(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        executor.execute(request.correlationId, request.toCreateSakMutation()) { data -> data.nySak.toSakResponse(request) }

    override suspend fun extendSak(request: ArbeidsgiversakExtendRequest): ArbeidsgiverNotificationResponse =
        executor.execute(request.correlationId, request.toExtendSakMutation()) { data ->
            data.skedulerHardDeleteSakByGrupperingsid.toSakExtendResponse()
        }

    override suspend fun setSaksstatus(request: ArbeidsgiversakStatusRequest): ArbeidsgiverNotificationResponse =
        executor.execute(request.idempotencyKey, request.toSetSaksstatusMutation()) { data ->
            data.nyStatusSakByGrupperingsid.toSaksstatusResponse()
        }

    override suspend fun createKalenderavtale(request: KalenderavtaleCreateRequest): ArbeidsgiverNotificationResponse =
        executor.execute(request.correlationId, request.toCreateKalenderavtaleMutation()) { data ->
            data.nyKalenderavtale.toKalenderavtaleCreateResponse(request)
        }

    override suspend fun updateKalenderavtale(request: KalenderavtaleUpdateRequest): ArbeidsgiverNotificationResponse =
        executor.execute(request.idempotencyKey, request.toUpdateKalenderavtaleMutation()) { data ->
            data.oppdaterKalenderavtaleByEksternId.toKalenderavtaleUpdateResponse()
        }

    private fun ArbeidsgiversakCreateRequest.toCreateSakMutation() =
        CreateSakMutation(
            grupperingsid = grupperingsid,
            merkelapp = tag,
            virksomhetsnummer = virksomhetsnummer,
            mottakere = listOf(recipient.toGraphqlRecipient()),
            tittel = title,
            lenke = link.toOptional(),
            hardDelete = keepUntil.toHardDeleteInput(),
        )

    private fun ArbeidsgiversakStatusRequest.toSetSaksstatusMutation() =
        SetSaksstatusMutation(
            idempotencyKey = Optional.present(idempotencyKey),
            grupperingsid = grupperingsid,
            merkelapp = tag,
            nyStatus = status.toGraphql(),
        )

    /** Only ever moves the deletion later, so concurrent deliveries cannot shorten the sak's life. */
    private fun ArbeidsgiversakExtendRequest.toExtendSakMutation() =
        ExtendSakMutation(
            merkelapp = tag,
            grupperingsid = grupperingsid,
            hardDelete = HardDeleteUpdateInput(nyTid = keepUntil.toHardDeleteInput(), strategi = NyTidStrategi.FORLENG),
        )

    private fun KalenderavtaleCreateRequest.toCreateKalenderavtaleMutation() =
        CreateKalenderavtaleMutation(
            virksomhetsnummer = virksomhetsnummer,
            grupperingsid = grupperingsid,
            merkelapp = tag,
            eksternId = eksternId,
            tekst = text,
            lenke = link,
            mottakere = listOf(recipient.toGraphqlRecipient()),
            startTidspunkt = startAt.toOsloOffsetDateTime(),
            sluttTidspunkt = endAt?.toOsloOffsetDateTime().toOptional(),
            lokasjon =
                lokasjon
                    ?.let { LokasjonInput(adresse = it.adresse, postnummer = it.postnummer, poststed = it.poststed) }
                    .toOptional(),
            erDigitalt = Optional.present(digital),
            tilstand = Optional.present(tilstand.toGraphql()),
            eksterneVarsler = recipient.externalVarsling?.toKalenderavtaleEmails().orEmpty(),
        )

    private fun KalenderavtaleUpdateRequest.toUpdateKalenderavtaleMutation() =
        UpdateKalenderavtaleMutation(
            merkelapp = tag,
            eksternId = eksternId,
            nyTilstand = tilstand?.toGraphql().toOptional(),
            nyTekst = text.toOptional(),
            idempotencyKey = Optional.present(idempotencyKey),
            eksterneVarsler = externalVarsling?.toKalenderavtaleEmails().orEmpty(),
        )

    /**
     * Kalenderavtale emails use Fager's own contact-centre window instead of budstikka's: the event itself
     * must exist at once so later updates can address it, and Fager drops unsent emails when the
     * event is cancelled.
     */
    private fun NarmesteLederExternalVarsling.toKalenderavtaleEmails(): List<EksterntVarselInput> =
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

    private suspend fun CreateSakMutation.NySak.toSakResponse(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        when {
            onNySakVellykket != null -> ArbeidsgiverNotificationResponse.Published
            // Fager answers an identical replay with success, so a duplicate means the key exists with
            // other content, for example a title with a changed name. The existing sak is checked.
            onDuplikatGrupperingsid != null ->
                verifyExistingSak(request, onDuplikatGrupperingsid.idTilEksisterende)
            onDuplikatGrupperingsidEtterDelete != null ->
                ArbeidsgiverNotificationResponse.SakDeleted(
                    "Arbeidsgiver notification API rejected request: DuplikatGrupperingsidEtterDelete",
                )
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null -> rejected("UgyldigMottaker")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            onUkjentRolle != null -> rejected("UkjentRolle")
            else -> error("Arbeidsgiver notification API returned an unexpected NySak result")
        }

    private fun SetSaksstatusMutation.NyStatusSakByGrupperingsid.toSaksstatusResponse() =
        when {
            onNyStatusSakVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onSakFinnesIkke != null -> notYetAvailable("SakFinnesIkke")
            onKonflikt != null -> rejected("Konflikt")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected NyStatusSak result")
        }

    private fun ExtendSakMutation.SkedulerHardDeleteSakByGrupperingsid.toSakExtendResponse() =
        when {
            onSkedulerHardDeleteSakVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onSakFinnesIkke != null -> notYetAvailable("SakFinnesIkke")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected SkedulerHardDeleteSak result")
        }

    private suspend fun CreateKalenderavtaleMutation.NyKalenderavtale.toKalenderavtaleCreateResponse(request: KalenderavtaleCreateRequest) =
        when {
            onNyKalenderavtaleVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onSakFinnesIkke != null -> notYetAvailable("SakFinnesIkke")
            // A duplicate means the eksternId exists with other content: our own earlier attempt that
            // has since been updated, or a reference reused by mistake. The existing event is checked.
            onDuplikatEksternIdOgMerkelapp != null ->
                verifyExistingKalenderavtale(request, onDuplikatEksternIdOgMerkelapp.idTilEksisterende)
            onUgyldigKalenderavtale != null -> rejected("UgyldigKalenderavtale")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUgyldigMottaker != null -> rejected("UgyldigMottaker")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected NyKalenderavtale result")
        }

    private fun UpdateKalenderavtaleMutation.OppdaterKalenderavtaleByEksternId.toKalenderavtaleUpdateResponse() =
        when {
            onOppdaterKalenderavtaleVellykket != null -> ArbeidsgiverNotificationResponse.Published
            onNotifikasjonFinnesIkke != null -> notYetAvailable("NotifikasjonFinnesIkke")
            onKonflikt != null -> rejected("Konflikt")
            onUgyldigKalenderavtale != null -> rejected("UgyldigKalenderavtale")
            onUgyldigMerkelapp != null -> rejected("UgyldigMerkelapp")
            onUkjentProdusent != null -> rejected("UkjentProdusent")
            else -> error("Arbeidsgiver notification API returned an unexpected OppdaterKalenderavtale result")
        }

    /** The existing sak is ours when it belongs to the same organisation; grupperingsid and tag already match. */
    private suspend fun verifyExistingSak(
        request: ArbeidsgiversakCreateRequest,
        existingId: String,
    ): ArbeidsgiverNotificationResponse =
        executor.execute(request.correlationId, FetchSakQuery(id = existingId)) { data ->
            val existing = data.hentSak.onHentetSak?.sak
            when {
                existing == null -> rejected("DuplikatGrupperingsid that could not be verified")
                existing.virksomhetsnummer == request.virksomhetsnummer -> ArbeidsgiverNotificationResponse.Published
                else -> rejected("DuplikatGrupperingsid in another organisation")
            }
        }

    /** The existing notification is ours when it is a kalenderavtale in the same sak. */
    private suspend fun verifyExistingKalenderavtale(
        request: KalenderavtaleCreateRequest,
        existingId: String,
    ): ArbeidsgiverNotificationResponse =
        executor.execute(request.correlationId, FetchNotifikasjonQuery(id = existingId)) { data ->
            val existing = data.hentNotifikasjon.onHentetNotifikasjon?.notifikasjon
            when {
                existing == null -> rejected("DuplikatEksternIdOgMerkelapp that could not be verified")
                existing.onKalenderavtale?.metadata?.grupperingsid == request.grupperingsid ->
                    ArbeidsgiverNotificationResponse.Published
                else -> rejected("DuplikatEksternIdOgMerkelapp for another sak or notification type")
            }
        }

    private fun notYetAvailable(resultType: String) =
        ArbeidsgiverNotificationResponse.NotYetAvailable(
            "Arbeidsgiver notification API has not found the target yet: $resultType",
        )

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

    private companion object {
        private val OSLO_ZONE_ID = ZoneId.of("Europe/Oslo")
    }
}
