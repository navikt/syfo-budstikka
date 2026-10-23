package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates and updates calendar events in a producer-owned case. The dispatch reference is the
 * event's eksternId downstream, so an update addresses the event by the same reference. Only a
 * Nærmeste leder recipient is supported. A requested email without a leader email address fails
 * terminally. A case that does not exist yet is retried; a leader who is not on the case is terminal,
 * because a calendar event cannot exist outside its case.
 */
class KalenderavtaleChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DeliveryMetrics,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome =
        when (val payload = delivery.payload) {
            is KalenderavtaleCreate -> create(delivery, payload)
            is KalenderavtaleUpdate -> update(delivery, payload)
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match KALENDERAVTALE channel: ${payload::class.simpleName}",
                )
        }

    private suspend fun create(
        delivery: ClaimedDelivery,
        create: KalenderavtaleCreate,
    ): DeliveryOutcome {
        listOf(
            "tag" to create.tag,
            "sakstilknytning.sakId" to create.sakstilknytning.sakId,
            "text" to create.text,
            "link" to create.link,
        ).firstOrNull { (_, value) -> value.isBlank() }
            ?.let { (name, _) -> return DeliveryOutcome.Failed("KALENDERAVTALE $name must not be blank") }
        val recipient =
            when (val resolved = resolveLeader(create.recipient, create.orgnummer)) {
                is ResolvedRecipient.Failure -> return resolved.outcome
                is ResolvedRecipient.Leader -> resolved.recipient
            }
        return withChannelHandlerFailureContext(Channel.KALENDERAVTALE, "creating calendar event") {
            publisher.createCalendarEvent(
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = create.orgnummer.value,
                    grupperingsid = create.sakstilknytning.sakId,
                    tag = create.tag,
                    eksternId = delivery.reference,
                    tekst = create.text,
                    lenke = create.link,
                    startAt = create.startAt,
                    endAt = create.endAt,
                    lokasjon = create.location,
                    erDigitalt = create.digital,
                    tilstand = create.state,
                    recipient = recipient,
                ),
            )
        }.toDeliveryOutcome(Channel.KALENDERAVTALE)
    }

    private suspend fun update(
        delivery: ClaimedDelivery,
        update: KalenderavtaleUpdate,
    ): DeliveryOutcome {
        if (update.tag.isBlank()) return DeliveryOutcome.Failed("KALENDERAVTALE tag must not be blank")
        if (update.newState == null && update.newText.isNullOrBlank() && !update.recipient.requestsEmail()) {
            return DeliveryOutcome.Failed("KALENDERAVTALE update has no change")
        }
        val externalVarsling =
            if (update.recipient.requestsEmail()) {
                when (val resolved = resolveLeader(update.recipient, update.orgnummer)) {
                    is ResolvedRecipient.Failure -> return resolved.outcome
                    is ResolvedRecipient.Leader -> resolved.recipient.externalVarsling
                }
            } else {
                null
            }
        return withChannelHandlerFailureContext(Channel.KALENDERAVTALE, "updating calendar event") {
            publisher.updateCalendarEvent(
                KalenderavtaleUpdateRequest(
                    tag = update.tag,
                    eksternId = delivery.reference,
                    nyTilstand = update.newState,
                    nyTekst = update.newText,
                    externalVarsling = externalVarsling,
                    idempotencyKey = delivery.correlationId(),
                ),
            )
        }.toDeliveryOutcome(Channel.KALENDERAVTALE)
    }

    private suspend fun resolveLeader(
        recipient: ArbeidsgiverRecipient,
        orgnummer: Orgnummer,
    ): ResolvedRecipient {
        val leader =
            recipient as? NarmesteLederRecipient
                ?: return ResolvedRecipient.Failure(
                    DeliveryOutcome.Failed("KALENDERAVTALE supports only a NarmesteLeder recipient"),
                )
        val requested = leader.externalVarsling
        if (requested != null && requested.emailTitle.isBlank()) {
            return ResolvedRecipient.Failure(
                DeliveryOutcome.Failed("KALENDERAVTALE external notification emailTitle must not be blank"),
            )
        }
        val emailHtmlBody = requested?.resolvedEmailHtmlBody()
        if (requested != null && emailHtmlBody == null) {
            return ResolvedRecipient.Failure(
                DeliveryOutcome.Failed("KALENDERAVTALE external notification emailText must not be blank"),
            )
        }
        return when (
            val resolution =
                narmesteLederLookup.resolveForDelivery(
                    channel = Channel.KALENDERAVTALE,
                    sykmeldt = leader.sykmeldt,
                    orgnummer = orgnummer,
                    requireEmail = requested != null,
                    metrics = metrics,
                )
        ) {
            is LeaderResolution.Missing -> ResolvedRecipient.Failure(resolution.outcome)
            is LeaderResolution.Found ->
                ResolvedRecipient.Leader(
                    ArbeidsgiverNotificationRecipient.NarmesteLeder(
                        narmesteLederFnr = resolution.relation.narmesteLederFnr,
                        ansattFnr = leader.sykmeldt,
                        externalVarsling =
                            requested?.let {
                                NarmesteLederExternalVarsling(
                                    epostTittel = it.emailTitle,
                                    epostHtmlBody = requireNotNull(emailHtmlBody),
                                    epostadresser = resolution.relation.epostadresser,
                                )
                            },
                    ),
                )
        }
    }

    private fun ArbeidsgiverRecipient.requestsEmail(): Boolean = (this as? NarmesteLederRecipient)?.externalVarsling != null

    private sealed interface ResolvedRecipient {
        data class Leader(
            val recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
        ) : ResolvedRecipient

        data class Failure(
            val outcome: DeliveryOutcome.Failed,
        ) : ResolvedRecipient
    }
}
