package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Optional
import io.ktor.client.HttpClient
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakExtendRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.infrastructure.auth.TokenProvider
import no.nav.budstikka.infrastructure.client.config.ArbeidsgiverNotifikasjonConfig
import no.nav.budstikka.infrastructure.client.fager.generated.CreateSakMutation
import no.nav.budstikka.infrastructure.client.fager.generated.ExtendSakMutation
import no.nav.budstikka.infrastructure.client.fager.generated.FetchSakQuery
import no.nav.budstikka.infrastructure.client.fager.generated.SetSaksstatusMutation
import no.nav.budstikka.infrastructure.client.fager.generated.type.HardDeleteUpdateInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyTidStrategi
import no.nav.budstikka.infrastructure.client.fager.generated.type.SaksStatus

/**
 * GraphQL anti-corruption adapter for the sak mutations of arbeidsgiver-notifikasjon-produsent-api,
 * which budstikka owns through [ArbeidsgiversakPublisher]. Saker are addressed by grupperingsid through
 * the `ByGrupperingsid` mutations, so no fager id is stored. Transport, authentication and status
 * handling are shared with [ArbeidsgiverNotifikasjonClient] through [FagerGraphqlExecutor].
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

    private fun notYetAvailable(resultType: String) =
        ArbeidsgiverNotificationResponse.NotYetAvailable(
            "Arbeidsgiver notification API has not found the target yet: $resultType",
        )

    private fun Saksstatus.toGraphql(): SaksStatus =
        when (this) {
            Saksstatus.MOTTATT -> SaksStatus.MOTTATT
            Saksstatus.UNDER_BEHANDLING -> SaksStatus.UNDER_BEHANDLING
            Saksstatus.FERDIG -> SaksStatus.FERDIG
        }
}
