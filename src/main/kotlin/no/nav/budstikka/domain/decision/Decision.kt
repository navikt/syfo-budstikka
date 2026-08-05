package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import kotlin.time.Instant

/** Neutral delivery channel persisted as `delivery.channel`; adapters own downstream formats. */
enum class Channel { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL, BREV, MICROFRONTEND }

/** `INACTIVATE` also represents a microfrontend visibility disable operation. */
enum class Operation { CREATE, INACTIVATE }

enum class DropReason { DEAD }

/**
 * Matching and partition anchor persisted as `delivery.recipient_id`. Identifier value types mask
 * their values in logs.
 */
sealed interface Recipient {
    data class Person(
        val ident: PersonIdentifier,
    ) : Recipient

    data class Virksomhet(
        val orgnummer: Orgnummer,
    ) : Recipient
}

/**
 * Frozen draft for one Delivery (one Channel, one Recipient), produced by [DecisionProcess.process].
 * Route attributes are frozen here. Registered channel adapters translate [content] at send time.
 * Drafts for channels without a registered handler remain unclaimed, so the draft deliberately
 * retains the source content.
 */
data class DeliveryDraft(
    val reference: String,
    val operation: Operation,
    val channel: Channel,
    val recipient: Recipient,
    val content: DispatchContent,
)

/**
 * Result of deciding one inbox event. Input-fetch failures are thrown instead of represented here,
 * leaving the claimed row available after its lease expires.
 */
sealed interface Decision {
    data class Processed(
        val deliveries: List<DeliveryDraft>,
    ) : Decision

    data class NotInSendingWindow(
        val nextRetry: Instant,
        val reason: String,
    ) : Decision

    data class Dropped(
        val reason: DropReason,
    ) : Decision

    data class Failed(
        val errorMessage: String,
    ) : Decision
}
