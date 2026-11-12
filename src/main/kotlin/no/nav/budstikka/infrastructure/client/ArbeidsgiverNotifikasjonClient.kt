package no.nav.budstikka.infrastructure.client

import com.apollographql.apollo.api.Optional
import io.ktor.client.HttpClient
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
import no.nav.budstikka.infrastructure.client.fager.generated.type.MetadataInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NotifikasjonInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyBeskjedInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.NyOppgaveInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.SendetidspunktInput
import no.nav.budstikka.infrastructure.client.fager.generated.type.Sendevindu

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
    httpClient: HttpClient,
    config: ArbeidsgiverNotifikasjonConfig,
    tokenProvider: TokenProvider,
) : ArbeidsgiverNotificationPublisher {
    private val executor = FagerGraphqlExecutor(httpClient, config, tokenProvider)

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        when (request.meldingstype) {
            ArbeidsgiverMeldingstype.BESKJED ->
                executor.execute(request.correlationId, request.toNyBeskjedMutation()) { data ->
                    data.nyBeskjed.toNotificationResponse()
                }
            ArbeidsgiverMeldingstype.OPPGAVE ->
                executor.execute(request.correlationId, request.toNyOppgaveMutation()) { data ->
                    data.nyOppgave.toNotificationResponse()
                }
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

    private fun ongoingSendTime() =
        SendetidspunktInput(
            sendevindu = Optional.present(Sendevindu.LOEPENDE),
        )
}
