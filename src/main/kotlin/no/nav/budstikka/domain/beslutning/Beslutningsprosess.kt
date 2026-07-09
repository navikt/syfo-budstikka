package no.nav.budstikka.domain.beslutning

import no.nav.budstikka.domain.formidling.Formidling

/**
 * Imperativt skall (B28) rundt den rene [decide]: henter grunnlag (I/O) og fatter beslutning for
 * én hendelse. Dette er inngangen den kommende beslutnings-workeren kaller per plukket rad.
 *
 * Bevisst UTENFOR denne slicen (utsatt til #19-fundamentet finnes):
 * - poll-løkka mot `inbox_hendelse` (`FOR UPDATE SKIP LOCKED`),
 * - effektuering: skriving av leveranse-rad(er) + `inbox_hendelse.status` i én DB-tx,
 * - retry/backoff ved transient grunnlags-I/O-feil.
 * Beslutningsutfallet ([Beslutning]) er nettopp dataen den effektueringen skal skrive.
 */
class Beslutningsprosess(
    private val grunnlagsinnhenter: Grunnlagsinnhenter,
) {
    suspend fun behandle(hendelse: Formidling): Beslutning = decide(hendelse, grunnlagsinnhenter.innhent(hendelse))
}
