package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier

/**
 * Channels that budstikka can route to (B27). One neutral channel abstraction; downstream forms
 * never leak in (B22). Used as `leveranse.kanal` (see `docs/datamodell.md`).
 */
enum class Channel { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL, BREV, MICROFRONTEND }

/** CREATE = a new send; INACTIVATE = closing/ferdigstill of a previous CREATE (B21/B38). */
enum class Operation { CREATE, INACTIVATE }

/** Why an event was dropped without a delivery. Currently only the death gate (“do not send to a dead person”). */
enum class DropReason { DEAD, }

/**
 * Matching/partition anchor (B5) for a delivery = `mottaker_id` in `docs/datamodell.md`. PII is
 * masked in logs through value types (B9). Resolved nærmeste leder (B24) is NOT held here: it is
 * frozen onto the delivery payload later, not used as a matching key.
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
 * Frozen draft for one delivery (one channel, one recipient), produced by pure [decide]. Route
 * attributes are frozen here; detailed channel DTO payload freezing (area 3, see
 * `docs/datamodell.md`) is deferred, so source [content] continues unchanged.
 */
data class DeliveryDraft(
    val reference: String,
    val operation: Operation,
    val channel: Channel,
    val recipient: Recipient,
    val content: DispatchContent,
)

/**
 * Result of the pure decision (B28) for one inbox event. Mirrors state transitions on
 * `inbox_hendelse.status` (see `docs/datamodell.md`): [Processed]→`BEHANDLET`,
 * [Dropped]→`DROPPET`, [Failed]→`FEILET`. A transient failure (PDL/KRR unavailable) is NOT a result
 * here: it occurs while fetching inputs (I/O) and is handled by the shell with backoff.
 */
sealed interface Decision {
    data class Processed(
        val deliveries: List<DeliveryDraft>,
    ) : Decision

    data class Dropped(
        val reason: DropReason,
    ) : Decision

    data class Failed(
        val errorMessage: String,
    ) : Decision
}
