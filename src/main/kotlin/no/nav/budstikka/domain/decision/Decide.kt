package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.dispatch.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.dispatch.BrevCreate
import no.nav.budstikka.domain.dispatch.BrukervarselCreate
import no.nav.budstikka.domain.dispatch.BrukervarselInactivate
import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.DittSykefravaerCreate
import no.nav.budstikka.domain.dispatch.DittSykefravaerInactivate
import no.nav.budstikka.domain.dispatch.LedervarselCreate
import no.nav.budstikka.domain.dispatch.LedervarselInactivate
import no.nav.budstikka.domain.dispatch.MicrofrontendDisable
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier

/**
 * Den rene beslutningskjernen (functional core, B28): ingen I/O, total og deterministisk.
 * All gate- og rutelogikk bor her, slik at den kan enhetstestes uten containere.
 *
 * Død-gaten (B-nivå: «ikke send til død person») dropper en brukerrettet CREATE når [foundation]
 * sier mottakeren er død. Lukkeoperasjoner (INACTIVATE/deaktiver) gates IKKE – de sender ikke til
 * personen, men rydder opp. Ledervarsel/arbeidsgivervarsel gates heller ikke på den sykmeldtes
 * død her: mottakeren er lederen/virksomheten, og NL-resolusjon (B24) mangler ennå – den
 * semantikken er en åpen beslutning som tas når de kanalene bygges (#22/#23).
 */
fun decide(
    event: Dispatch,
    foundation: DecisionFoundation,
): Decision {
    val content = event.content
    if (foundation.recipientIsDead && content.gatedPerson() != null) {
        return Decision.Dropped(DropReason.DEAD)
    }
    return Decision.Processed(listOf(content.toDeliveryDraft(event.reference)))
}

/**
 * Personen død-gaten gjelder for, eller `null` når hendelsen ikke er en brukerrettet CREATE.
 * Styrer også hvilke hendelser [FoundationFetcher] i det hele tatt slår opp død for – vi kaller
 * ikke PDL når svaret uansett ikke kan gate.
 *
 * `when` er bevisst totalt (ingen `else`): en ny [DispatchContent]-variant skal gi kompilerings-
 * feil her, slik at gate-beslutningen tas eksplisitt og ingen ny brukerrettet CREATE stille slipper
 * forbi død-gaten.
 */
internal fun DispatchContent.gatedPerson(): PersonIdentifier? =
    when (this) {
        is BrukervarselCreate -> personIdentifier
        is DittSykefravaerCreate -> personIdentifier
        is BrevCreate -> personIdentifier
        is MicrofrontendEnable -> personIdentifier
        is BrukervarselInactivate,
        is LedervarselCreate,
        is LedervarselInactivate,
        is DittSykefravaerInactivate,
        is ArbeidsgivervarselCreate,
        is ArbeidsgivervarselInactivate,
        is MicrofrontendDisable,
        -> null
    }

private fun DispatchContent.toDeliveryDraft(reference: String): DeliveryDraft =
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
