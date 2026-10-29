package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiversakStatus
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Sets the status of a producer-owned case in Arbeidsgivernotifikasjoner. The case is made to exist
 * first, so a status change also works for a new leader whose case has no content yet. A Nærmeste
 * leder recipient is resolved at send time and selects the case of the current leader relation.
 * Budstikka keeps no case state.
 */
class ArbeidsgiversakChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DeliveryMetrics,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val status =
            delivery.payload as? ArbeidsgiversakStatus
                ?: return DeliveryOutcome.Failed(
                    "Payload does not match ARBEIDSGIVERSAK channel: ${delivery.payload::class.simpleName}",
                )
        if (status.tag.isBlank()) return DeliveryOutcome.Failed("ARBEIDSGIVERSAK tag must not be blank")
        status.sakstilknytning.invalidCaseDescription(Channel.ARBEIDSGIVERSAK)?.let { return it }
        val (recipient, lederrelasjon) =
            when (val recipient = status.recipient) {
                is AltinnResource ->
                    if (recipient.resource.isBlank()) {
                        return DeliveryOutcome.Failed("ARBEIDSGIVERSAK recipient.resource must not be blank")
                    } else {
                        ArbeidsgiverNotificationRecipient.AltinnRessurs(recipient.resource) to null
                    }
                is NarmesteLederRecipient ->
                    when (
                        val resolution =
                            narmesteLederLookup.resolveForDelivery(
                                channel = Channel.ARBEIDSGIVERSAK,
                                sykmeldt = recipient.sykmeldt,
                                orgnummer = status.orgnummer,
                                requireEmail = false,
                                metrics = metrics,
                            )
                    ) {
                        is LeaderResolution.Missing -> return resolution.outcome
                        is LeaderResolution.Found ->
                            ArbeidsgiverNotificationRecipient.NarmesteLeder(
                                narmesteLederFnr = resolution.relation.narmesteLederFnr,
                                ansattFnr = recipient.sykmeldt,
                            ) to resolution.relation.relasjonId
                    }
            }
        val grupperingsid = status.sakstilknytning.grupperingsid(lederrelasjon)
        publisher
            .ensureCase(
                channel = Channel.ARBEIDSGIVERSAK,
                sak = status.sakstilknytning,
                grupperingsid = grupperingsid,
                virksomhetsnummer = status.orgnummer.value,
                tag = status.tag,
                recipient = recipient,
                correlationId = delivery.correlationId(),
            )?.let { return it }
        return withChannelHandlerFailureContext(Channel.ARBEIDSGIVERSAK, "updating case status") {
            publisher.updateCaseStatus(
                ArbeidsgiversakStatusRequest(
                    grupperingsid = grupperingsid,
                    tag = status.tag,
                    status = status.status,
                    statustekst = status.statusText,
                    hardDeleteAt = status.sakstilknytning.hardDeleteAt,
                    idempotencyKey = delivery.correlationId(),
                ),
            )
        }.toDeliveryOutcome(Channel.ARBEIDSGIVERSAK)
    }
}
