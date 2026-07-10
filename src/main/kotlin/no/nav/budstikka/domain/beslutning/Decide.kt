package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.ArbeidsgivervarselCreate
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.formidling.BrevCreate
import no.nav.budstikka.domain.formidling.BrukervarselCreate
import no.nav.budstikka.domain.formidling.BrukervarselInactivate
import no.nav.budstikka.domain.formidling.DittSykefravaerCreate
import no.nav.budstikka.domain.formidling.DittSykefravaerInactivate
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselCreate
import no.nav.budstikka.domain.formidling.LedervarselInactivate
import no.nav.budstikka.domain.formidling.MikrofrontendDisable
import no.nav.budstikka.domain.formidling.MikrofrontendEnable
import no.nav.budstikka.domain.formidling.Personident

/**
 * Den rene beslutningskjernen (functional core, B28): ingen I/O, total og deterministisk.
 * All gate- og rutelogikk bor her, slik at den kan enhetstestes uten containere.
 *
 * Død-gaten (B-nivå: «ikke send til død person») dropper en brukerrettet CREATE når [grunnlag]
 * sier mottakeren er død. Lukkeoperasjoner (INACTIVATE/deaktiver) gates IKKE – de sender ikke til
 * personen, men rydder opp. Ledervarsel/arbeidsgivervarsel gates heller ikke på den sykmeldtes
 * død her: mottakeren er lederen/virksomheten, og NL-resolusjon (B24) mangler ennå – den
 * semantikken er en åpen beslutning som tas når de kanalene bygges (#22/#23).
 */
fun decide(
    hendelse: Formidling,
    grunnlag: Beslutningsgrunnlag,
): Beslutning {
    val content = hendelse.content
    if (grunnlag.mottakerIsDead && content.gatedPerson() != null) {
        return Beslutning.Dropped(DropReason.DEAD)
    }
    return Beslutning.Processed(listOf(content.toLeveranseDraft(hendelse.referanse)))
}

/**
 * Personen død-gaten gjelder for, eller `null` når hendelsen ikke er en brukerrettet CREATE.
 * Styrer også hvilke hendelser [GrunnlagFetcher] i det hele tatt slår opp død for – vi kaller
 * ikke PDL når svaret uansett ikke kan gate.
 *
 * `when` er bevisst totalt (ingen `else`): en ny [Formidlingsinnhold]-variant skal gi kompilerings-
 * feil her, slik at gate-beslutningen tas eksplisitt og ingen ny brukerrettet CREATE stille slipper
 * forbi død-gaten.
 */
internal fun Formidlingsinnhold.gatedPerson(): Personident? =
    when (this) {
        is BrukervarselCreate -> personident
        is DittSykefravaerCreate -> personident
        is BrevCreate -> personident
        is MikrofrontendEnable -> personident
        is BrukervarselInactivate,
        is LedervarselCreate,
        is LedervarselInactivate,
        is DittSykefravaerInactivate,
        is ArbeidsgivervarselCreate,
        is ArbeidsgivervarselInactivate,
        is MikrofrontendDisable,
        -> null
    }

private fun Formidlingsinnhold.toLeveranseDraft(referanse: String): LeveranseDraft =
    when (this) {
        is BrukervarselCreate ->
            draft(referanse, Operation.CREATE, Kanal.BRUKERVARSEL, Mottaker.Person(personident))
        is BrukervarselInactivate ->
            draft(referanse, Operation.INACTIVATE, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt))
        is LedervarselCreate ->
            draft(referanse, Operation.CREATE, Kanal.LEDERVARSEL, Mottaker.Person(sykmeldt))
        is LedervarselInactivate ->
            draft(referanse, Operation.INACTIVATE, Kanal.LEDERVARSEL, Mottaker.Person(sykmeldt))
        is DittSykefravaerCreate ->
            draft(referanse, Operation.CREATE, Kanal.DITT_SYKEFRAVAER, Mottaker.Person(personident))
        is DittSykefravaerInactivate ->
            draft(referanse, Operation.INACTIVATE, Kanal.DITT_SYKEFRAVAER, Mottaker.Person(sykmeldt))
        is ArbeidsgivervarselCreate ->
            draft(referanse, Operation.CREATE, Kanal.ARBEIDSGIVERVARSEL, Mottaker.Virksomhet(orgnummer))
        is ArbeidsgivervarselInactivate ->
            draft(referanse, Operation.INACTIVATE, Kanal.ARBEIDSGIVERVARSEL, Mottaker.Virksomhet(orgnummer))
        is BrevCreate ->
            draft(referanse, Operation.CREATE, Kanal.BREV, Mottaker.Person(personident))
        is MikrofrontendEnable ->
            draft(referanse, Operation.CREATE, Kanal.MIKROFRONTEND, Mottaker.Person(personident))
        is MikrofrontendDisable ->
            draft(referanse, Operation.INACTIVATE, Kanal.MIKROFRONTEND, Mottaker.Person(personident))
    }

private fun Formidlingsinnhold.draft(
    referanse: String,
    operation: Operation,
    kanal: Kanal,
    mottaker: Mottaker,
): LeveranseDraft = LeveranseDraft(referanse, operation, kanal, mottaker, this)
