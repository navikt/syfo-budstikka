package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.Sakstype
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.PersonName
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliverySnapshot
import kotlin.time.Instant
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Creates and updates kalenderavtaler using the leader and name saved when the delivery was
 * decided. The dispatch reference is the event's downstream eksternId, so an update addresses the
 * event by the same reference. Only a Nærmeste leder recipient is supported. A create lands in the
 * Budstikka-owned sakstype sak of the saved leader relation, which lives at least four months past
 * the event. An update changes only the event; a status it carries is set on that sak first.
 */
class KalenderavtaleChannelHandler(
    private val publisher: ArbeidsgiversakPublisher,
    private val saker: Arbeidsgiversaker,
) : ChannelHandler {
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val snapshot =
            delivery.snapshot as? DeliverySnapshot.Resolved
                ?: return failed("legacy delivery snapshot is unsupported")
        val leader = snapshot.leader ?: return failed("resolved delivery snapshot is missing a leader")
        return when (val payload = delivery.payload) {
            is KalenderavtaleCreate -> create(delivery, payload, leader, snapshot.name)
            is KalenderavtaleUpdate -> update(delivery, payload, leader, snapshot.name)
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match KALENDERAVTALE channel: ${payload::class.simpleName}",
                )
        }
    }

    /** Creates the event in the saved leader relation's sak, and sets the requested status after it. */
    private suspend fun create(
        delivery: ClaimedDelivery,
        create: KalenderavtaleCreate,
        leader: NarmesteLederRelasjon,
        name: PersonName?,
    ): DeliveryOutcome {
        val recipient =
            create.recipient as? NarmesteLederRecipient
                ?: return failed("supports only a NarmesteLeder recipient")
        recipient.deliveryReason(leader)?.let { return failed(it) }
        create.invalidReason()?.let { return failed(it) }
        val sak =
            when (
                val resolution =
                    leaderSak(
                        delivery,
                        create.sakstype,
                        create.orgnummer.value,
                        recipient.toSaksmottaker(leader),
                        name,
                        create.endAt ?: create.startAt,
                    )
            ) {
                is SakResolution.Failed -> return resolution.outcome
                is SakResolution.Ready -> resolution
            }
        val outcome =
            withChannelHandlerFailureContext(CHANNEL, "creating kalenderavtale") {
                publisher.createKalenderavtale(create.toRequest(delivery, sak, recipient.toNotificationRecipient(leader)))
            }.toDeliveryOutcome(CHANNEL)
        val status = create.saksstatus
        if (outcome != DeliveryOutcome.Sent || status == null) return outcome
        return saker.setStatus(CHANNEL, sak, status, delivery.correlationId()) ?: outcome
    }

    /**
     * Updates the event where it was created. Email and status go to the saved leader. The status
     * is set first: a failure there is retried without repeating the update and its email, which
     * Fager does not deduplicate.
     */
    private suspend fun update(
        delivery: ClaimedDelivery,
        update: KalenderavtaleUpdate,
        leader: NarmesteLederRelasjon,
        name: PersonName?,
    ): DeliveryOutcome {
        val recipient =
            update.recipient as? NarmesteLederRecipient
                ?: return failed("supports only a NarmesteLeder recipient")
        recipient.deliveryReason(leader)?.let { return failed(it) }
        update.invalidReason()?.let { return failed(it) }
        val status = update.saksstatus
        if (status != null) {
            val sak =
                when (
                    val resolution =
                        leaderSak(
                            delivery,
                            update.sakstype,
                            update.orgnummer.value,
                            recipient.toSaksmottaker(leader),
                            name,
                        )
                ) {
                    is SakResolution.Failed -> return resolution.outcome
                    is SakResolution.Ready -> resolution
                }
            saker.setStatus(CHANNEL, sak, status, delivery.correlationId())?.let { return it }
        }
        return withChannelHandlerFailureContext(CHANNEL, "updating kalenderavtale") {
            publisher.updateKalenderavtale(
                update.toRequest(delivery, recipient.toNotificationRecipient(leader).externalVarsling),
            )
        }.toDeliveryOutcome(CHANNEL)
    }

    /** The leader relation's [sakstype] sak, created when needed and kept past [activityAt]. */
    private suspend fun leaderSak(
        delivery: ClaimedDelivery,
        sakstype: Sakstype,
        orgnummer: String,
        mottaker: Saksmottaker.NarmesteLeder,
        name: PersonName?,
        activityAt: Instant? = null,
    ): SakResolution =
        saker.ensure(
            channel = CHANNEL,
            sakstype = sakstype,
            virksomhetsnummer = orgnummer,
            mottaker = mottaker,
            name = name,
            correlationId = delivery.correlationId(),
            activityAt = activityAt,
        )
}

private val CHANNEL = Channel.KALENDERAVTALE

private fun failed(reason: String) = DeliveryOutcome.Failed("${CHANNEL.name} $reason")

private fun NarmesteLederRecipient.deliveryReason(leader: NarmesteLederRelasjon): String? =
    invalidEmailReason()
        ?: if (externalVarsling != null && leader.epostadresser.isEmpty()) {
            "NarmesteLeder delivery unavailable: missing_email_address"
        } else {
            null
        }

/** Why the event cannot be created, or `null`; a producer writing the wire format directly can send it anyway. */
private fun KalenderavtaleCreate.invalidReason(): String? =
    when {
        text.isBlank() -> "text must not be blank"
        link.isBlank() -> "link must not be blank"
        text.length > MAX_KALENDERAVTALE_TEXT_LENGTH -> "text must be at most $MAX_KALENDERAVTALE_TEXT_LENGTH characters"
        else -> null
    }

private fun ArbeidsgiverRecipient.requestsEmail(): Boolean = (this as? NarmesteLederRecipient)?.externalVarsling != null

/** Why the event cannot be updated, or `null`. */
private fun KalenderavtaleUpdate.invalidReason(): String? =
    when {
        (text?.length ?: 0) > MAX_KALENDERAVTALE_TEXT_LENGTH -> "text must be at most $MAX_KALENDERAVTALE_TEXT_LENGTH characters"
        tilstand == null && text.isNullOrBlank() && !recipient.requestsEmail() -> "update has no change"
        else -> null
    }

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
