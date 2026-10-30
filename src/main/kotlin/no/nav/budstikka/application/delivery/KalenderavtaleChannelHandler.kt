package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.domain.decision.Channel
import java.util.UUID
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates and updates calendar events. The dispatch reference is the event's eksternId downstream,
 * so an update addresses the event by the same reference. Only a Nærmeste leder recipient is
 * supported. A create lands in the Budstikka-owned saktype case of the current leader relation, which
 * lives at least four months past the event; an update extends the case the event lives in. A requested
 * email without a leader email address fails terminally.
 */
class KalenderavtaleChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val saker: Arbeidsgiversaker,
    private val narmesteLederLookup: NarmesteLederLookup,
    private val metrics: DeliveryMetrics,
) : ChannelHandler {
    private val logger = applicationLogger(KalenderavtaleChannelHandler::class.java)

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
            "text" to create.text,
            "link" to create.link,
        ).firstOrNull { (_, value) -> value.isBlank() }
            ?.let { (name, _) -> return DeliveryOutcome.Failed("KALENDERAVTALE $name must not be blank") }
        if (create.text.length > MAX_CALENDAR_TEXT_LENGTH) {
            return DeliveryOutcome.Failed("KALENDERAVTALE text must be at most $MAX_CALENDAR_TEXT_LENGTH characters")
        }
        val leader =
            when (val resolved = resolveLeader(create.recipient, create.orgnummer)) {
                is ResolvedRecipient.Failure -> return resolved.outcome
                is ResolvedRecipient.Leader -> resolved
            }
        val sak =
            when (
                val resolution =
                    saker.ensure(
                        channel = Channel.KALENDERAVTALE,
                        saktype = create.saktype,
                        virksomhetsnummer = create.orgnummer.value,
                        mottaker =
                            Saksmottaker.NarmesteLeder(
                                relasjonId = leader.relasjonId,
                                narmesteLederFnr = leader.recipient.narmesteLederFnr,
                                sykmeldt = leader.recipient.ansattFnr,
                            ),
                        correlationId = delivery.correlationId(),
                        activityAt = create.endAt ?: create.startAt,
                    )
            ) {
                is SakResolution.Failed -> return resolution.outcome
                is SakResolution.Ready -> resolution
            }
        val outcome =
            withChannelHandlerFailureContext(Channel.KALENDERAVTALE, "creating calendar event") {
                publisher.createCalendarEvent(
                    KalenderavtaleCreateRequest(
                        virksomhetsnummer = create.orgnummer.value,
                        grupperingsid = sak.grupperingsid,
                        tag = sak.merkelapp,
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
        val status = create.saksstatus
        if (outcome != DeliveryOutcome.Sent || status == null) return outcome
        return saker.setStatus(Channel.KALENDERAVTALE, sak, status, delivery.correlationId()) ?: outcome
    }

    private suspend fun update(
        delivery: ClaimedDelivery,
        update: KalenderavtaleUpdate,
    ): DeliveryOutcome {
        if ((update.text?.length ?: 0) > MAX_CALENDAR_TEXT_LENGTH) {
            return DeliveryOutcome.Failed("KALENDERAVTALE text must be at most $MAX_CALENDAR_TEXT_LENGTH characters")
        }
        val status = update.saksstatus
        if (update.tilstand == null && update.text.isNullOrBlank() && !update.recipient.requestsEmail() && status == null) {
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
        val response =
            withChannelHandlerFailureContext(Channel.KALENDERAVTALE, "updating calendar event") {
                publisher.updateCalendarEvent(
                    KalenderavtaleUpdateRequest(
                        tag = update.saktype.merkelapp,
                        eksternId = delivery.reference,
                        nyTilstand = update.tilstand,
                        nyTekst = update.text,
                        externalVarsling = externalVarsling,
                        idempotencyKey = delivery.correlationId(),
                    ),
                )
            }
        val grupperingsid =
            when (response) {
                is KalenderavtaleUpdateResponse.NotUpdated -> return response.response.toDeliveryOutcome(Channel.KALENDERAVTALE)
                is KalenderavtaleUpdateResponse.Updated -> response.grupperingsid ?: return DeliveryOutcome.Sent
            }
        // The event's own case, also after a leader change.
        val sak = SakResolution.Ready(grupperingsid, update.saktype.merkelapp)
        if (externalVarsling == null) return followUp(sak, status, delivery) ?: DeliveryOutcome.Sent
        // A retry would repeat the update, and Fager does not deduplicate the email of a calendar update.
        return try {
            followUp(sak, status, delivery) ?: DeliveryOutcome.Sent
        } catch (_: ChannelHandlerFailure) {
            logger.event(DeliveryLogEvents.calendarCaseFollowUpSkipped)
            DeliveryOutcome.Sent
        }
    }

    /** Keeps the event's case alive past the update and sets the status a producer asked for. */
    private suspend fun followUp(
        sak: SakResolution.Ready,
        status: Saksstatus?,
        delivery: ClaimedDelivery,
    ): DeliveryOutcome.Failed? =
        saker.extend(Channel.KALENDERAVTALE, sak, delivery.correlationId())
            ?: status?.let { saker.setStatus(Channel.KALENDERAVTALE, sak, it, delivery.correlationId()) }

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
        ) : ResolvedRecipient {
            override fun toString(): String = "Leader(recipient=$recipient)"
        }

        data class Failure(
            val outcome: DeliveryOutcome.Failed,
        ) : ResolvedRecipient
    }
}

/** Arbeidsgivernotifikasjoner rejects longer calendar texts. */
private const val MAX_CALENDAR_TEXT_LENGTH = 300
