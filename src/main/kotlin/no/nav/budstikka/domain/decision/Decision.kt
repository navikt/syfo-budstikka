package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import kotlin.time.Instant

/**
 * Channels that budstikka can route to (B27). One neutral channel abstraction; downstream forms
 * never leak in (B22). Persisted as `delivery.channel`.
 */
enum class Channel { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL, BREV, MICROFRONTEND }

/**
 * CREATE starts a new Dispatch. INACTIVATE represents either Ferdigstill of an earlier Dispatch or
 * the visibility toggle for MicrofrontendDisable (B21/B38/B41).
 */
enum class Operation { CREATE, INACTIVATE }

/** Why an event was dropped without a delivery. Currently only the death gate (“do not send to a dead person”). */
enum class DropReason { DEAD, }

/**
 * Matching and partition anchor (B5) for a Delivery, persisted as `delivery.recipient_id`. PII is
 * masked in logs through value types (B9). For Ledervarsel, this is the Sykmeldt until a future
 * channel adapter resolves Nærmeste leder (B24); that lookup is not implemented yet.
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
 * Result of the pure decision (B28) for one inbox event. The application layer maps [Processed],
 * [Dropped], and [Failed] to `PROCESSED`, `DROPPED`, and `FAILED` in `inbox_message.state`.
 * A transient failure (PDL/KRR unavailable) is NOT a result here: input fetching throws, and the
 * claim remains available for retry after its lease expires.
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
