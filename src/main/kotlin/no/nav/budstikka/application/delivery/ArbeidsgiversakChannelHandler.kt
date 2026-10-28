package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiversakCreate
import no.nav.budstikka.contract.ArbeidsgiversakStatus
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates producer-owned cases and sets their status in Arbeidsgivernotifikasjoner. The case is
 * addressed by the producer's case identity (grupperingsid), so budstikka keeps no case state. A
 * Nærmeste leder recipient is resolved at send time, like for ARBEIDSGIVERVARSEL; content linked to
 * the case must later reach the same leader.
 */
class ArbeidsgiversakChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DeliveryMetrics,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome =
        when (val payload = delivery.payload) {
            is ArbeidsgiversakCreate -> create(delivery, payload)
            is ArbeidsgiversakStatus -> updateStatus(delivery, payload)
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match ARBEIDSGIVERSAK channel: ${payload::class.simpleName}",
                )
        }

    private suspend fun create(
        delivery: ClaimedDelivery,
        create: ArbeidsgiversakCreate,
    ): DeliveryOutcome {
        blankField(
            "tag" to create.tag,
            "sakstilknytning.sakId" to create.sakstilknytning.sakId,
            "title" to create.title,
        )?.let { return it }
        if (create.title.length > MAX_CASE_TITLE_LENGTH) {
            return DeliveryOutcome.Failed("ARBEIDSGIVERSAK title must be at most $MAX_CASE_TITLE_LENGTH characters")
        }
        val recipient =
            when (val recipient = create.recipient) {
                is AltinnResource ->
                    if (recipient.resource.isBlank()) {
                        return DeliveryOutcome.Failed("ARBEIDSGIVERSAK recipient.resource must not be blank")
                    } else {
                        ArbeidsgiverNotificationRecipient.AltinnRessurs(recipient.resource)
                    }
                is NarmesteLederRecipient ->
                    when (
                        val resolution =
                            narmesteLederLookup.resolveForDelivery(
                                channel = Channel.ARBEIDSGIVERSAK,
                                sykmeldt = recipient.sykmeldt,
                                orgnummer = create.orgnummer,
                                requireEmail = false,
                                metrics = metrics,
                            )
                    ) {
                        is LeaderResolution.Missing -> return resolution.outcome
                        is LeaderResolution.Found ->
                            ArbeidsgiverNotificationRecipient.NarmesteLeder(
                                narmesteLederFnr = resolution.relation.narmesteLederFnr,
                                ansattFnr = recipient.sykmeldt,
                            )
                    }
            }
        return withChannelHandlerFailureContext(Channel.ARBEIDSGIVERSAK, "creating case") {
            publisher.createCase(
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = create.orgnummer.value,
                    grupperingsid = create.sakstilknytning.sakId,
                    tag = create.tag,
                    tittel = create.title,
                    lenke = create.link,
                    initiellStatus = create.initialStatus,
                    hardDeleteAt = create.hardDeleteAt,
                    recipient = recipient,
                    correlationId = delivery.correlationId(),
                ),
            )
        }.toDeliveryOutcome(Channel.ARBEIDSGIVERSAK)
    }

    private suspend fun updateStatus(
        delivery: ClaimedDelivery,
        status: ArbeidsgiversakStatus,
    ): DeliveryOutcome {
        blankField(
            "tag" to status.tag,
            "sakstilknytning.sakId" to status.sakstilknytning.sakId,
        )?.let { return it }
        return withChannelHandlerFailureContext(Channel.ARBEIDSGIVERSAK, "updating case status") {
            publisher.updateCaseStatus(
                ArbeidsgiversakStatusRequest(
                    grupperingsid = status.sakstilknytning.sakId,
                    tag = status.tag,
                    status = status.status,
                    statustekst = status.statusText,
                    hardDeleteAt = status.hardDeleteAt,
                    idempotencyKey = delivery.correlationId(),
                ),
            )
        }.toDeliveryOutcome(Channel.ARBEIDSGIVERSAK)
    }

    private fun blankField(vararg fields: Pair<String, String>): DeliveryOutcome.Failed? =
        fields
            .firstOrNull { (_, value) -> value.isBlank() }
            ?.let { (name, _) -> DeliveryOutcome.Failed("ARBEIDSGIVERSAK $name must not be blank") }
}

/** Arbeidsgivernotifikasjoner rejects longer case titles. */
private const val MAX_CASE_TITLE_LENGTH = 140
