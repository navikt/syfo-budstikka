package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Saksstatus
import no.nav.budstikka.contract.Sakstilknytning
import no.nav.budstikka.domain.decision.Channel
import java.util.UUID

/**
 * Stable per dispatch across retries and replicas: the inbox event id, or the delivery id for a
 * derived delivery without one. Used as correlation header and downstream idempotency key.
 */
internal fun ClaimedDelivery.correlationId(): String = (inboxEventId ?: id).toString()

/**
 * Maps an Arbeidsgivernotifikasjoner response to a delivery outcome. [ArbeidsgiverNotificationResponse.NotYetAvailable]
 * throws so the delivery stays claimed and is retried after its lease, within the attempt budget.
 */
internal fun ArbeidsgiverNotificationResponse.toDeliveryOutcome(channel: Channel): DeliveryOutcome =
    when (this) {
        ArbeidsgiverNotificationResponse.Published -> DeliveryOutcome.Sent
        is ArbeidsgiverNotificationResponse.Rejected -> DeliveryOutcome.Failed(reason)
        is ArbeidsgiverNotificationResponse.NotYetAvailable ->
            throw ChannelHandlerFailure(channel, "waiting for a downstream object", IllegalStateException(reason))
    }

/** Result of resolving the active Nærmeste leder for a delivery. */
internal sealed interface LeaderResolution {
    data class Found(
        val relation: NarmesteLederRelasjon,
    ) : LeaderResolution

    data class Missing(
        val outcome: DeliveryOutcome.Failed,
    ) : LeaderResolution
}

/**
 * Resolves the active leader at send time. A missing leader, or a missing email address when
 * [requireEmail] is set, is a terminal, metered failure without identifiers.
 */
internal suspend fun NarmesteLederLookup.resolveForDelivery(
    channel: Channel,
    sykmeldt: PersonIdentifier,
    orgnummer: Orgnummer,
    requireEmail: Boolean,
    metrics: DeliveryMetrics,
): LeaderResolution {
    val relation =
        withChannelHandlerFailureContext(channel, "resolving nærmeste leder") {
            findActive(sykmeldt, orgnummer)
        } ?: return LeaderResolution.Missing(leaderFailure(channel, NarmesteLederMissingReason.MISSING_ACTIVE_LEADER, metrics))
    if (requireEmail && relation.epostadresser.isEmpty()) {
        return LeaderResolution.Missing(leaderFailure(channel, NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS, metrics))
    }
    return LeaderResolution.Found(relation)
}

private fun leaderFailure(
    channel: Channel,
    reason: NarmesteLederMissingReason,
    metrics: DeliveryMetrics,
): DeliveryOutcome.Failed {
    metrics.narmesteLederMissing(reason)
    return DeliveryOutcome.Failed("${channel.name} NarmesteLeder delivery unavailable: ${reason.name.lowercase()}")
}

/** Arbeidsgivernotifikasjoner rejects longer case titles. */
internal const val MAX_CASE_TITLE_LENGTH = 140

/**
 * The case's grouping id downstream. A Nærmeste leder case belongs to the leader relation, so a new
 * leader gets a case of their own for the same producer case; an Altinn case is the producer case. A
 * link-only association (no title, from the deprecated overloads) points at a case created elsewhere
 * and is used unchanged.
 */
internal fun Sakstilknytning.grupperingsid(lederrelasjon: UUID?): String =
    if (title == null || lederrelasjon == null) sakId else "$sakId:$lederrelasjon"

/** Validation of a case description, without echoing its values. */
internal fun Sakstilknytning.invalidCaseDescription(channel: Channel): DeliveryOutcome.Failed? {
    val title = title ?: return DeliveryOutcome.Failed("${channel.name} sakstilknytning.title must be set")
    return when {
        sakId.isBlank() -> DeliveryOutcome.Failed("${channel.name} sakstilknytning.sakId must not be blank")
        title.isBlank() -> DeliveryOutcome.Failed("${channel.name} sakstilknytning.title must not be blank")
        title.length > MAX_CASE_TITLE_LENGTH ->
            DeliveryOutcome.Failed("${channel.name} sakstilknytning.title must be at most $MAX_CASE_TITLE_LENGTH characters")
        else -> null
    }
}

/**
 * Makes sure the described case exists for [recipient] before content is created in it. Creating an
 * identical case again succeeds downstream, and a case that already exists with other content is
 * verified to be ours, so this is safe to repeat on every delivery. Returns a terminal failure, or
 * `null` when the case exists.
 */
internal suspend fun ArbeidsgiversakPublisher.ensureCase(
    channel: Channel,
    sak: Sakstilknytning,
    grupperingsid: String,
    virksomhetsnummer: String,
    tag: String,
    recipient: ArbeidsgiverNotificationRecipient,
    correlationId: String,
): DeliveryOutcome.Failed? {
    val response =
        withChannelHandlerFailureContext(channel, "ensuring case") {
            createCase(
                ArbeidsgiversakCreateRequest(
                    virksomhetsnummer = virksomhetsnummer,
                    grupperingsid = grupperingsid,
                    tag = tag,
                    tittel = requireNotNull(sak.title),
                    lenke = sak.link,
                    initiellStatus = Saksstatus.MOTTATT,
                    hardDeleteAt = sak.hardDeleteAt,
                    recipient = recipient.withoutExternalVarsling(),
                    correlationId = correlationId,
                ),
            )
        }
    return when (val outcome = response.toDeliveryOutcome(channel)) {
        DeliveryOutcome.Sent -> null
        is DeliveryOutcome.Failed -> outcome
    }
}

/** A case has no external notification of its own; only its content notifies. */
private fun ArbeidsgiverNotificationRecipient.withoutExternalVarsling(): ArbeidsgiverNotificationRecipient =
    when (this) {
        is ArbeidsgiverNotificationRecipient.AltinnRessurs -> copy(externalVarsling = null)
        is ArbeidsgiverNotificationRecipient.NarmesteLeder -> copy(externalVarsling = null)
    }
