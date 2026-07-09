package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.ArbeidsgivervarselInaktiver
import no.nav.budstikka.domain.formidling.ArbeidsgivervarselOpprett
import no.nav.budstikka.domain.formidling.BrevOpprett
import no.nav.budstikka.domain.formidling.BrukervarselInaktiver
import no.nav.budstikka.domain.formidling.BrukervarselOpprett
import no.nav.budstikka.domain.formidling.DittSykefravaerInaktiver
import no.nav.budstikka.domain.formidling.DittSykefravaerOpprett
import no.nav.budstikka.domain.formidling.Formidling
import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.LedervarselInaktiver
import no.nav.budstikka.domain.formidling.LedervarselOpprett
import no.nav.budstikka.domain.formidling.MikrofrontendAktiver
import no.nav.budstikka.domain.formidling.MikrofrontendDeaktiver
import no.nav.budstikka.domain.formidling.Personident

/**
 * Den rene beslutningskjernen (functional core, B28): ingen I/O, total og deterministisk.
 * All gate- og rutelogikk bor her, slik at den kan enhetstestes uten containere.
 *
 * Død-gaten (B-nivå: «ikke send til død person») dropper en brukerrettet OPPRETT når [grunnlag]
 * sier mottakeren er død. Lukkeoperasjoner (INAKTIVER/deaktiver) gates IKKE – de sender ikke til
 * personen, men rydder opp. Ledervarsel/arbeidsgivervarsel gates heller ikke på den sykmeldtes
 * død her: mottakeren er lederen/virksomheten, og NL-resolusjon (B24) mangler ennå – den
 * semantikken er en åpen beslutning som tas når de kanalene bygges (#22/#23).
 */
fun decide(
    hendelse: Formidling,
    grunnlag: Beslutningsgrunnlag,
): Beslutning {
    val innhold = hendelse.innhold
    if (grunnlag.mottakerErDod && innhold.gatetPerson() != null) {
        return Beslutning.Droppet(DropAarsak.DOD)
    }
    return Beslutning.Behandlet(listOf(innhold.tilLeveranseUtkast(hendelse.referanse)))
}

/**
 * Personen død-gaten gjelder for, eller `null` når hendelsen ikke er en brukerrettet OPPRETT.
 * Styrer også hvilke hendelser [Grunnlagsinnhenter] i det hele tatt slår opp død for – vi kaller
 * ikke PDL når svaret uansett ikke kan gate.
 *
 * `when` er bevisst totalt (ingen `else`): en ny [Formidlingsinnhold]-variant skal gi kompilerings-
 * feil her, slik at gate-beslutningen tas eksplisitt og ingen ny brukerrettet OPPRETT stille slipper
 * forbi død-gaten.
 */
internal fun Formidlingsinnhold.gatetPerson(): Personident? =
    when (this) {
        is BrukervarselOpprett -> personident
        is DittSykefravaerOpprett -> personident
        is BrevOpprett -> personident
        is MikrofrontendAktiver -> personident
        is BrukervarselInaktiver -> null
        is LedervarselOpprett -> null
        is LedervarselInaktiver -> null
        is DittSykefravaerInaktiver -> null
        is ArbeidsgivervarselOpprett -> null
        is ArbeidsgivervarselInaktiver -> null
        is MikrofrontendDeaktiver -> null
    }

private fun Formidlingsinnhold.tilLeveranseUtkast(referanse: String): LeveranseUtkast =
    when (this) {
        is BrukervarselOpprett ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.BRUKERVARSEL, Mottaker.Person(personident))
        is BrukervarselInaktiver ->
            utkast(referanse, Operasjon.INAKTIVER, Kanal.BRUKERVARSEL, Mottaker.Person(sykmeldt))
        is LedervarselOpprett ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.LEDERVARSEL, Mottaker.Person(sykmeldt))
        is LedervarselInaktiver ->
            utkast(referanse, Operasjon.INAKTIVER, Kanal.LEDERVARSEL, Mottaker.Person(sykmeldt))
        is DittSykefravaerOpprett ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.DITT_SYKEFRAVAER, Mottaker.Person(personident))
        is DittSykefravaerInaktiver ->
            utkast(referanse, Operasjon.INAKTIVER, Kanal.DITT_SYKEFRAVAER, Mottaker.Person(sykmeldt))
        is ArbeidsgivervarselOpprett ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.ARBEIDSGIVERVARSEL, Mottaker.Virksomhet(orgnummer))
        is ArbeidsgivervarselInaktiver ->
            utkast(referanse, Operasjon.INAKTIVER, Kanal.ARBEIDSGIVERVARSEL, Mottaker.Virksomhet(orgnummer))
        is BrevOpprett ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.BREV, Mottaker.Person(personident))
        is MikrofrontendAktiver ->
            utkast(referanse, Operasjon.OPPRETT, Kanal.MIKROFRONTEND, Mottaker.Person(personident))
        is MikrofrontendDeaktiver ->
            utkast(referanse, Operasjon.INAKTIVER, Kanal.MIKROFRONTEND, Mottaker.Person(personident))
    }

private fun Formidlingsinnhold.utkast(
    referanse: String,
    operasjon: Operasjon,
    kanal: Kanal,
    mottaker: Mottaker,
): LeveranseUtkast = LeveranseUtkast(referanse, operasjon, kanal, mottaker, this)
