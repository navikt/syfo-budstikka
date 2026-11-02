package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.decision.Channel
import kotlin.time.Instant
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates and updates kalenderavtaler. The dispatch reference is the event's eksternId downstream,
 * so an update addresses the event by the same reference. Only a Nærmeste leder recipient is
 * supported. A create lands in the Budstikka-owned sakstype sak of the current leader relation, which
 * lives at least four months past the event. An update changes only the event; a status it carries is
 * set on the current leader relation's sak first. A requested email without a leader email address
 * fails terminally.
 */
class KalenderavtaleChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val saker: Arbeidsgiversaker,
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

    /** Creates the event in the current leader relation's sak, and sets the requested status after it. */
    private suspend fun create(
        delivery: ClaimedDelivery,
        create: KalenderavtaleCreate,
    ): DeliveryOutcome {
        create.invalidReason()?.let { return failed(it) }
        val leader =
            when (val found = findLeader(create.recipient, create.orgnummer)) {
                is LeaderLookup.Failed -> return found.outcome
                is LeaderLookup.Found -> found
            }
        val sak =
            when (val resolution = leaderSak(delivery, create.sakstype, create.orgnummer, leader.sak, create.endAt ?: create.startAt)) {
                is SakResolution.Failed -> return resolution.outcome
                is SakResolution.Ready -> resolution
            }
        val outcome =
            withChannelHandlerFailureContext(CHANNEL, "creating kalenderavtale") {
                publisher.createKalenderavtale(create.toRequest(delivery, sak, leader.recipient))
            }.toDeliveryOutcome(CHANNEL)
        val status = create.saksstatus
        if (outcome != DeliveryOutcome.Sent || status == null) return outcome
        return saker.setStatus(CHANNEL, sak, status, delivery.correlationId()) ?: outcome
    }

    /**
     * Updates the event where it was created. Email and status go to the current leader, who is looked up
     * only when one of them is requested. The status is set first: a failure there is retried without
     * repeating the update and its email, which Fager does not deduplicate.
     */
    private suspend fun update(
        delivery: ClaimedDelivery,
        update: KalenderavtaleUpdate,
    ): DeliveryOutcome {
        update.invalidReason()?.let { return failed(it) }
        val status = update.saksstatus
        val leader =
            if (update.recipient.requestsEmail() || status != null) {
                when (val found = findLeader(update.recipient, update.orgnummer)) {
                    is LeaderLookup.Failed -> return found.outcome
                    is LeaderLookup.Found -> found
                }
            } else {
                null
            }
        if (status != null && leader != null) {
            val sak =
                when (val resolution = leaderSak(delivery, update.sakstype, update.orgnummer, leader.sak)) {
                    is SakResolution.Failed -> return resolution.outcome
                    is SakResolution.Ready -> resolution
                }
            saker.setStatus(CHANNEL, sak, status, delivery.correlationId())?.let { return it }
        }
        return withChannelHandlerFailureContext(CHANNEL, "updating kalenderavtale") {
            publisher.updateKalenderavtale(update.toRequest(delivery, leader?.recipient?.externalVarsling))
        }.toDeliveryOutcome(CHANNEL)
    }

    /** The active leader for [recipient], who must be a Nærmeste leder; a missing leader is metered. */
    private suspend fun findLeader(
        recipient: ArbeidsgiverRecipient,
        orgnummer: Orgnummer,
    ): LeaderLookup {
        val target = recipient as? NarmesteLederRecipient ?: return LeaderLookup.Failed(failed("supports only a NarmesteLeder recipient"))
        target.invalidEmailReason()?.let { return LeaderLookup.Failed(failed(it)) }
        return when (
            val found =
                narmesteLederLookup.resolveForDelivery(
                    channel = CHANNEL,
                    sykmeldt = target.sykmeldt,
                    orgnummer = orgnummer,
                    requireEmail = target.externalVarsling != null,
                    metrics = metrics,
                )
        ) {
            is LeaderResolution.Missing -> LeaderLookup.Failed(found.outcome)
            is LeaderResolution.Found ->
                LeaderLookup.Found(target.toNotificationRecipient(found.relation), target.toSaksmottaker(found.relation))
        }
    }

    /** The leader relation's [sakstype] sak, created when needed and kept past [activityAt]. */
    private suspend fun leaderSak(
        delivery: ClaimedDelivery,
        sakstype: Sakstype,
        orgnummer: Orgnummer,
        mottaker: Saksmottaker.NarmesteLeder,
        activityAt: Instant? = null,
    ): SakResolution = saker.ensure(CHANNEL, sakstype, orgnummer.value, mottaker, delivery.correlationId(), activityAt)

    /** The active leader as kalenderavtale recipient, with the leader relation's sak; or why there is none. */
    private sealed interface LeaderLookup {
        class Found(
            val recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
            val sak: Saksmottaker.NarmesteLeder,
        ) : LeaderLookup

        class Failed(
            val outcome: DeliveryOutcome.Failed,
        ) : LeaderLookup
    }
}

private val CHANNEL = Channel.KALENDERAVTALE

private fun failed(reason: String) = DeliveryOutcome.Failed("${CHANNEL.name} $reason")

/** Why the event cannot be created, or `null`; a producer writing the wire format directly can send it anyway. */
private fun KalenderavtaleCreate.invalidReason(): String? =
    when {
        text.isBlank() -> "text must not be blank"
        link.isBlank() -> "link must not be blank"
        text.length > MAX_KALENDERAVTALE_TEXT_LENGTH -> "text must be at most $MAX_KALENDERAVTALE_TEXT_LENGTH characters"
        else -> null
    }

/** Why the event cannot be updated, or `null`. */
private fun KalenderavtaleUpdate.invalidReason(): String? =
    when {
        (text?.length ?: 0) > MAX_KALENDERAVTALE_TEXT_LENGTH -> "text must be at most $MAX_KALENDERAVTALE_TEXT_LENGTH characters"
        tilstand == null && text.isNullOrBlank() && !recipient.requestsEmail() -> "update has no change"
        else -> null
    }

private fun ArbeidsgiverRecipient.requestsEmail(): Boolean = (this as? NarmesteLederRecipient)?.externalVarsling != null

private fun KalenderavtaleCreate.toRequest(
    delivery: ClaimedDelivery,
    sak: SakResolution.Ready,
    recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
) = KalenderavtaleCreateRequest(
    virksomhetsnummer = orgnummer.value,
    grupperingsid = sak.grupperingsid,
    tag = sak.merkelapp,
    eksternId = delivery.reference,
    text = text,
    link = link,
    startAt = startAt,
    endAt = endAt,
    lokasjon = lokasjon,
    digital = digital,
    tilstand = tilstand,
    recipient = recipient,
    correlationId = delivery.correlationId(),
)

private fun KalenderavtaleUpdate.toRequest(
    delivery: ClaimedDelivery,
    externalVarsling: NarmesteLederExternalVarsling?,
) = KalenderavtaleUpdateRequest(
    tag = sakstype.merkelapp,
    eksternId = delivery.reference,
    tilstand = tilstand,
    text = text,
    externalVarsling = externalVarsling,
    idempotencyKey = delivery.correlationId(),
)

/** Arbeidsgivernotifikasjoner rejects longer kalenderavtale texts. */
private const val MAX_KALENDERAVTALE_TEXT_LENGTH = 300
