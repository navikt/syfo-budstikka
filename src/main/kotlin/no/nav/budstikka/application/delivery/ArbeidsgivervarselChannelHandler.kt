package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.NarmesteLederRelasjon
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliverySnapshot
import no.nav.budstikka.contract.NarmesteLeder as NarmesteLederRecipient

/**
 * Sends ARBEIDSGIVERVARSEL through an [AltinnResource] or a Nærmeste leder path using
 * the leader and name saved at Decision, including on retry. Only explicitly marked legacy rows
 * use [LegacyDeliverySnapshotResolver]. External notifications are sent as LOEPENDE: [SendingWindowGate]
 * has already waited for budstikka's delivery window. A requested external notification without a
 * leader email fails the entire delivery terminally; it does not degrade to in-app only. With a
 * sakstype, the notification goes in the Budstikka-owned sak of that type for the recipient; without
 * one, it keeps the producer's tag and optional sak link. Closing remains a permanent failure.
 */
class ArbeidsgivervarselChannelHandler(
    private val publisher: ArbeidsgiverNotificationPublisher,
    private val saker: Arbeidsgiversaker,
    private val legacySnapshots: LegacyDeliverySnapshotResolver,
) : ChannelHandler {
    /** Validates the notification, finds its recipient, makes sure the sak exists, and sends it there. */
    override suspend fun handle(delivery: ClaimedDelivery): DeliveryOutcome {
        val create = delivery.payload as? ArbeidsgivervarselCreate ?: return payloadFailure(delivery)
        create.invalidReason()?.let { return DeliveryOutcome.Failed("${CHANNEL.name} $it") }

        val snapshot =
            when (val saved = delivery.snapshot) {
                is DeliverySnapshot.Resolved -> saved
                DeliverySnapshot.Legacy ->
                    when (val decision = legacySnapshots.resolve(delivery)) {
                        is Decision.Processed -> decision.deliveries.single().snapshot
                        is Decision.Failed -> return DeliveryOutcome.Failed(decision.errorMessage)
                        else -> error("Unexpected legacy snapshot decision")
                    }
            }
        val recipient =
            when (val target = create.recipient) {
                is AltinnResource -> target.toRecipient()
                is NarmesteLederRecipient -> target.toRecipient(checkNotNull(snapshot.leader) { "Resolved snapshot missing leader" })
            }

        val sak =
            create.sakstype?.let { sakstype ->
                when (
                    val resolution =
                        saker.ensure(
                            channel = CHANNEL,
                            sakstype = sakstype,
                            virksomhetsnummer = create.orgnummer.value,
                            mottaker = checkNotNull(recipient.saksmottaker) { "a sakstype is validated to have a saksmottaker" },
                            name = snapshot.name,
                            correlationId = delivery.correlationId(),
                            activityAt = create.visibleUntil,
                        )
                ) {
                    is SakResolution.Failed -> return resolution.outcome
                    is SakResolution.Ready -> resolution
                }
            }

        val link =
            when (create.recipient) {
                is AltinnResource -> requireNotNull(create.link) { "Altinn link is required" }
                is NarmesteLederRecipient -> {
                    val relationId = checkNotNull(snapshot.leader) { "Resolved snapshot missing leader" }.relationId
                    create.link?.replace("NARMESTE_LEDER_ID", relationId.toString()) ?: saker.dineSykmeldteLink(relationId)
                }
            }
        val outcome = publish(create.toRequest(delivery, recipient.notification, sak, link)).toDeliveryOutcome(CHANNEL)
        val status = create.saksstatus
        if (outcome != DeliveryOutcome.Sent || sak == null || status == null) return outcome
        return saker.setStatus(CHANNEL, sak, status, delivery.correlationId()) ?: outcome
    }

    private suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse =
        withChannelHandlerFailureContext(CHANNEL, "publishing notification") {
            publisher.publish(request)
        }

    private fun payloadFailure(delivery: ClaimedDelivery): DeliveryOutcome =
        when (delivery.payload) {
            is ArbeidsgivervarselInactivate ->
                DeliveryOutcome.Failed("ARBEIDSGIVERVARSEL inactivate is not implemented")
            else ->
                DeliveryOutcome.Failed(
                    "Payload does not match ARBEIDSGIVERVARSEL channel: ${delivery.payload::class.simpleName}",
                )
        }
}

/** Where the notification goes, and the sak it belongs to when it has a sakstype. */
private class Recipient(
    val notification: ArbeidsgiverNotificationRecipient,
    val saksmottaker: Saksmottaker?,
)

private val CHANNEL = Channel.ARBEIDSGIVERVARSEL

/**
 * Why the notification cannot be delivered, or `null`. The facade refuses these already, but a
 * producer that writes the wire format directly can still send them, so the failure is terminal.
 */
private fun ArbeidsgivervarselCreate.invalidReason(): String? =
    when {
        link?.isBlank() == true || (recipient is AltinnResource && link == null) -> "link must not be blank"
        sakstype == null && tag.isNullOrBlank() -> "tag must not be blank"
        sakstype != null && tag != null -> "tag must not be set with sakstype"
        sakstype == null && saksstatus != null -> "saksstatus needs a sakstype"
        else -> recipient.invalidReason(inSak = sakstype != null)
    }

private fun ArbeidsgiverRecipient.invalidReason(inSak: Boolean): String? =
    when (this) {
        is AltinnResource ->
            when {
                resource.isBlank() -> "recipient.resource must not be blank"
                externalVarsling?.emailTitle?.isBlank() == true -> "external notification emailTitle must not be blank"
                externalVarsling?.smsText?.isBlank() == true -> "external notification smsText must not be blank"
                externalVarsling != null && externalVarsling?.resolvedEmailHtmlBody() == null ->
                    "external notification emailText must not be blank"
                inSak && (sykmeldt == null || sakId.isNullOrBlank()) ->
                    "recipient.sykmeldt and recipient.sakId must be set with sakstype"
                inSak && !Saksmottaker.AltinnRessurs.isValidSakId(sakId.orEmpty()) ->
                    "recipient.sakId must be 1-64 letters, digits, '-' or '_'"
                else -> null
            }
        is NarmesteLederRecipient -> invalidEmailReason()
    }

private fun AltinnResource.toRecipient() =
    Recipient(
        notification =
            ArbeidsgiverNotificationRecipient.AltinnRessurs(
                resource = resource,
                externalVarsling =
                    externalVarsling?.let {
                        AltinnExternalVarsling(
                            epostTittel = it.emailTitle,
                            epostHtmlBody = requireNotNull(it.resolvedEmailHtmlBody()),
                            smsTekst = it.smsText,
                        )
                    },
            ),
        saksmottaker = sykmeldt?.let { sykmeldt -> sakId?.let { Saksmottaker.AltinnRessurs(resource, sykmeldt, it) } },
    )

private fun NarmesteLederRecipient.toRecipient(relation: NarmesteLederRelasjon) =
    Recipient(notification = toNotificationRecipient(relation), saksmottaker = toSaksmottaker(relation))

private fun ArbeidsgivervarselCreate.toRequest(
    delivery: ClaimedDelivery,
    recipient: ArbeidsgiverNotificationRecipient,
    sak: SakResolution.Ready?,
    link: String,
) = ArbeidsgiverNotificationRequest(
    virksomhetsnummer = orgnummer.value,
    eksternId = delivery.correlationId(),
    grupperingsid = sak?.grupperingsid ?: sakstilknytning?.sakId,
    tag = sak?.merkelapp ?: requireNotNull(tag),
    tekst = text,
    lenke = link,
    recipient = recipient,
    meldingstype = meldingstype,
    visibleUntil = visibleUntil,
)
