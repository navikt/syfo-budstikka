package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.Orgnummer
import no.nav.budstikka.domain.dispatch.PersonIdentifier

/**
 * Channelene budstikka kan rute til (B27). Én nøytral kanalabstraksjon; nedstrøms-former lekker
 * aldri inn (B22). Brukes som `leveranse.kanal` (jf. `docs/datamodell.md`).
 */
enum class Channel { BRUKERVARSEL, LEDERVARSEL, DITT_SYKEFRAVAER, ARBEIDSGIVERVARSEL, BREV, MICROFRONTEND }

/** CREATE = ny utsending; INACTIVATE = lukking/ferdigstill av en tidligere CREATE (B21/B38). */
enum class Operation { CREATE, INACTIVATE }

/** Hvorfor en hendelse ble droppet uten leveranse. Nå kun død-gaten («ikke send til død person»). */
enum class DropReason { DEAD, }

/**
 * Match-/partisjonsanker (B5) for en leveranse = `mottaker_id` i `docs/datamodell.md`. PII;
 * maskeres i logg via value-typene (B9). Resolvert nærmeste leder (B24) ligger IKKE her – den
 * fryses på leveranse-payloaden senere, ikke som matchnøkkel.
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
 * Frosset draft til én leveranse (én kanal, én mottaker) som den rene [decide]-funksjonen
 * produserer. Rute-attributtene fryses her; detaljert kanal-DTO-frysing av payload (område 3,
 * jf. `docs/datamodell.md`) er utsatt – derfor bæres kildeinnholdet [content] uendret videre.
 */
data class DeliveryDraft(
    val reference: String,
    val operation: Operation,
    val channel: Channel,
    val recipient: Recipient,
    val content: DispatchContent,
)

/**
 * Utfallet av den rene beslutningen (B28) for én inbox-hendelse. Speiler tilstandsovergangene
 * på `inbox_hendelse.status` (jf. `docs/datamodell.md`): [Processed]→`BEHANDLET`,
 * [Dropped]→`DROPPET`, [Failed]→`FEILET`. Transient feil (PDL/KRR nede) er IKKE et utfall her –
 * det oppstår i grunnlagsinnhentingen (I/O) og håndteres av skallet med backoff.
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
