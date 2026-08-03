package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.DittSykefravaerCreate
import no.nav.budstikka.domain.dispatch.DittSykefravaerInactivate
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.domain.dispatch.SendingWindow

/*
 * Pure mapping from [DispatchContent] to [DeliveryDraft] route attributes (channel, operation, and
 * recipient), and to the Sykmeldt a person gate (for example [DeathGate]) may apply to. No I/O;
 * total and deterministic, tested with pure data.
 */

/**
 * Sykmeldt a person gate applies to, or `null` when the event is not a Sykmeldt-directed CREATE.
 * Gates use this for self-selection: a gate without a gated person leaves the Delivery unchanged, and
 * [DeathGate] does not query PDL when the result cannot gate, nor does [SendingWindowGate] enforce any rules.
 *
 * The `when` is deliberately total (no `else`): a new [DispatchContent] variant must cause a
 * compilation error here, making the gate decision explicit and preventing a new Sykmeldt-directed
 * CREATE from silently bypassing person gates.
 */
internal fun DispatchContent.gatedPerson(): PersonIdentifier? =
    when (this) {
        is BrukervarselCreate -> personIdentifier
        is DittSykefravaerCreate -> personIdentifier
        is BrevCreate -> personIdentifier
        is BrukervarselInactivate,
        is LedervarselCreate,
        is LedervarselInactivate,
        is DittSykefravaerInactivate,
        is ArbeidsgivervarselCreate,
        is ArbeidsgivervarselInactivate,
        is MicrofrontendEnable,
        is MicrofrontendDisable,
        -> null
    }

internal fun DispatchContent.gatedSendingWindow(): SendingWindow? =
    when (this) {
        is BrukervarselCreate -> sendingWindow
        is LedervarselCreate -> sendingWindow
        is ArbeidsgivervarselCreate -> sendingWindow
        is DittSykefravaerCreate,
        is BrukervarselInactivate,
        is LedervarselInactivate,
        is DittSykefravaerInactivate,
        is ArbeidsgivervarselInactivate,
        is BrevCreate,
        is MicrofrontendEnable,
        is MicrofrontendDisable,
        -> null
    }

internal fun DispatchContent.toDeliveryDraft(reference: String): DeliveryDraft =
    when (this) {
        is BrukervarselCreate ->
            draft(reference, Operation.CREATE, Channel.BRUKERVARSEL, Recipient.Person(personIdentifier))

        is BrukervarselInactivate ->
            draft(reference, Operation.INACTIVATE, Channel.BRUKERVARSEL, Recipient.Person(sykmeldt))

        is LedervarselCreate ->
            draft(reference, Operation.CREATE, Channel.LEDERVARSEL, Recipient.Person(sykmeldt))

        is LedervarselInactivate ->
            draft(reference, Operation.INACTIVATE, Channel.LEDERVARSEL, Recipient.Person(sykmeldt))

        is DittSykefravaerCreate ->
            draft(reference, Operation.CREATE, Channel.DITT_SYKEFRAVAER, Recipient.Person(personIdentifier))

        is DittSykefravaerInactivate ->
            draft(reference, Operation.INACTIVATE, Channel.DITT_SYKEFRAVAER, Recipient.Person(sykmeldt))

        is ArbeidsgivervarselCreate ->
            draft(reference, Operation.CREATE, Channel.ARBEIDSGIVERVARSEL, Recipient.Virksomhet(orgnummer))

        is ArbeidsgivervarselInactivate ->
            draft(reference, Operation.INACTIVATE, Channel.ARBEIDSGIVERVARSEL, Recipient.Virksomhet(orgnummer))

        is BrevCreate ->
            draft(reference, Operation.CREATE, Channel.BREV, Recipient.Person(personIdentifier))

        is MicrofrontendEnable ->
            draft(reference, Operation.CREATE, Channel.MICROFRONTEND, Recipient.Person(personIdentifier))

        is MicrofrontendDisable ->
            draft(reference, Operation.INACTIVATE, Channel.MICROFRONTEND, Recipient.Person(personIdentifier))
    }

private fun DispatchContent.draft(
    reference: String,
    operation: Operation,
    channel: Channel,
    recipient: Recipient,
): DeliveryDraft = DeliveryDraft(reference, operation, channel, recipient, this)

/**
 * BREV Delivery derived from [BrukervarselCreate.brevFallback], or `null` when there is no
 * BrevFallback. [ReservationGate] calls this only for a Sykmeldt with Reservasjon (B8/ADR 0009).
 */
internal fun BrukervarselCreate.brevFallbackDraft(reference: String): DeliveryDraft? =
    brevFallback?.let { fallback ->
        DeliveryDraft(
            reference = reference,
            operation = Operation.CREATE,
            channel = Channel.BREV,
            recipient = Recipient.Person(personIdentifier),
            content =
                BrevCreate(
                    personIdentifier = personIdentifier,
                    journalpostId = fallback.journalpostId,
                    distributionType = fallback.distributionType,
                ),
        )
    }
