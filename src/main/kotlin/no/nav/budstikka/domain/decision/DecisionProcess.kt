package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * Imperativt skall (B28) rundt den rene [decide]: henter grunnlag (I/O) og fatter beslutning for
 * én hendelse. Dette er inngangen den kommende beslutnings-workeren kaller per plukket rad.
 *
 * Bevisst UTENFOR denne slicen (utsatt til #19-fundamentet finnes):
 * - poll-løkka mot `inbox_hendelse` (`FOR UPDATE SKIP LOCKED`),
 * - effektuering: skriving av leveranse-rad(er) + `inbox_hendelse.status` i én DB-tx,
 * - retry/backoff ved transient grunnlags-I/O-feil.
 * Decisionsutfallet ([Decision]) er nettopp dataen den effektueringen skal skrive.
 */
class DecisionProcess(
    private val foundationFetcher: FoundationFetcher,
) {
    suspend fun process(event: Dispatch): Decision = decide(event, foundationFetcher.fetch(event))
}
