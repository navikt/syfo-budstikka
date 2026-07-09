package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.Orgnummer
import no.nav.budstikka.domain.formidling.Personident

/**
 * Kanalene budstikka kan rute til (B27). Én nøytral kanalabstraksjon; nedstrøms-former lekker
 * aldri inn (B22). Brukes som `leveranse.kanal` (jf. `docs/DATAMODELL.md`).
 */
enum class Kanal { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL, BREV, MIKROFRONTEND }

/** OPPRETT = ny utsending; INAKTIVER = lukking/ferdigstill av en tidligere OPPRETT (B21/B38). */
enum class Operasjon { OPPRETT, INAKTIVER }

/** Hvorfor en hendelse ble droppet uten leveranse. Nå kun død-gaten («ikke send til død person»). */
enum class DropAarsak { DOD, }

/**
 * Match-/partisjonsanker (B5) for en leveranse = `mottaker_id` i `docs/DATAMODELL.md`. PII;
 * maskeres i logg via value-typene (B9). Resolvert nærmeste leder (B24) ligger IKKE her – den
 * fryses på leveranse-payloaden senere, ikke som matchnøkkel.
 */
sealed interface Mottaker {
    data class Person(
        val ident: Personident,
    ) : Mottaker

    data class Virksomhet(
        val orgnummer: Orgnummer,
    ) : Mottaker
}

/**
 * Frosset utkast til én leveranse (én kanal, én mottaker) som den rene [decide]-funksjonen
 * produserer. Rute-attributtene fryses her; detaljert kanal-DTO-frysing av payload (område 3,
 * jf. `docs/DATAMODELL.md`) er utsatt – derfor bæres kildeinnholdet [innhold] uendret videre.
 */
data class LeveranseUtkast(
    val referanse: String,
    val operasjon: Operasjon,
    val kanal: Kanal,
    val mottaker: Mottaker,
    val innhold: Formidlingsinnhold,
)

/**
 * Utfallet av den rene beslutningen (B28) for én inbox-hendelse. Speiler tilstandsovergangene
 * på `inbox_hendelse.status` (jf. `docs/DATAMODELL.md`): [Behandlet]→`BEHANDLET`,
 * [Droppet]→`DROPPET`, [Feilet]→`FEILET`. Transient feil (PDL/KRR nede) er IKKE et utfall her –
 * det oppstår i grunnlagsinnhentingen (I/O) og håndteres av skallet med backoff.
 */
sealed interface Beslutning {
    data class Behandlet(
        val leveranser: List<LeveranseUtkast>,
    ) : Beslutning

    data class Droppet(
        val aarsak: DropAarsak,
    ) : Beslutning

    data class Feilet(
        val feilmelding: String,
    ) : Beslutning
}
