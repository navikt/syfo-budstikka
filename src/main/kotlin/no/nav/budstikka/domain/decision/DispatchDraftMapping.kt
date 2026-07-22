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

/*
 * Ren mapping fra [DispatchContent] til rute-attributtene på et [DeliveryDraft] (kanal, operasjon,
 * mottaker), samt hvilken person en person-gate (f.eks. [DeathGate]) eventuelt gjelder for. Ingen
 * I/O, total og deterministisk – testes med rene data.
 */

/**
 * Personen en person-gate gjelder for, eller `null` når hendelsen ikke er en brukerrettet CREATE.
 * Gatene bruker dette til self-selection: en gate uten gated person slipper leveransen uendret
 * gjennom, og [DeathGate] slår ikke opp PDL når svaret uansett ikke kan gate.
 *
 * `when` er bevisst totalt (ingen `else`): en ny [DispatchContent]-variant skal gi kompilerings-
 * feil her, slik at gate-beslutningen tas eksplisitt og ingen ny brukerrettet CREATE stille slipper
 * forbi person-gatene.
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
 * BREV-leveransen en reservert brukers [BrukervarselCreate.brevFallback] gir opphav til (B8/ADR 0009),
 * eller `null` når hendelsen ikke har noen fallback.
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
