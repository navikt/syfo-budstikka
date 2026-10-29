package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.domain.decision.Channel
import java.util.UUID
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates and updates calendar events in a producer-owned case. The dispatch reference is the
 * event's eksternId downstream, so an update addresses the event by the same reference. Only a
 * Nærmeste leder recipient is supported. A create first makes sure the case of the current leader
 * relation exists, so the event lands in the case of the leader who receives it. A requested email
 * without a leader email address fails terminally.
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
            "text" to create.text,
            "link" to create.link,
        ).firstOrNull { (_, value) -> value.isBlank() }
            ?.let { (name, _) -> return DeliveryOutcome.Failed("KALENDERAVTALE $name must not be blank") }
        if (create.text.length > MAX_CALENDAR_TEXT_LENGTH) {
            return DeliveryOutcome.Failed("KALENDERAVTALE text must be at most $MAX_CALENDAR_TEXT_LENGTH characters")
        }
        create.sakstilknytning.invalidCaseDescription(Channel.KALENDERAVTALE)?.let { return it }
        val leader =
            when (val resolved = resolveLeader(create.recipient, create.orgnummer)) {
                is ResolvedRecipient.Failure -> return resolved.outcome
                is ResolvedRecipient.Leader -> resolved
            }
        val grupperingsid = create.sakstilknytning.grupperingsid(leader.relasjonId)
        publisher
            .ensureCase(
                channel = Channel.KALENDERAVTALE,
                sak = create.sakstilknytning,
                grupperingsid = grupperingsid,
                virksomhetsnummer = create.orgnummer.value,
                tag = create.tag,
                recipient = leader.recipient,
                correlationId = delivery.correlationId(),
            )?.let { return it }
        return withChannelHandlerFailureContext(Channel.KALENDERAVTALE, "creating calendar event") {
            publisher.createCalendarEvent(
                KalenderavtaleCreateRequest(
                    virksomhetsnummer = create.orgnummer.value,
                    grupperingsid = grupperingsid,
                    tag = create.tag,
                    eksternId = delivery.reference,
                    tekst = create.text,
                    lenke = create.link,
                    startAt = create.startAt,
                    endAt = create.endAt,
                    lokasjon = create.lokasjon,
                    erDigitalt = create.digital,
                    tilstand = create.tilstand,
                    recipient = leader.recipient,
                    correlationId = delivery.correlationId(),
                ),
            )
        }.toDeliveryOutcome(Channel.KALENDERAVTALE)
    }

    private suspend fun update(
        delivery: ClaimedDelivery,
        update: KalenderavtaleUpdate,
    ): DeliveryOutcome {
        if (update.tag.isBlank()) return DeliveryOutcome.Failed("KALENDERAVTALE tag must not be blank")
        if ((update.text?.length ?: 0) > MAX_CALENDAR_TEXT_LENGTH) {
            return DeliveryOutcome.Failed("KALENDERAVTALE text must be at most $MAX_CALENDAR_TEXT_LENGTH characters")
        }
        if (update.tilstand == null && update.text.isNullOrBlank() && !update.recipient.requestsEmail()) {
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
                    nyTilstand = update.tilstand,
                    nyTekst = update.text,
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
                    relasjonId = resolution.relation.relasjonId,
                    recipient =
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
            val relasjonId: UUID,
            val recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
        ) : ResolvedRecipient

        data class Failure(
            val outcome: DeliveryOutcome.Failed,
        ) : ResolvedRecipient
    }
}

/** Arbeidsgivernotifikasjoner rejects longer calendar texts. */
private const val MAX_CALENDAR_TEXT_LENGTH = 300
