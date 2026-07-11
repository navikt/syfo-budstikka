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

/**
 * Imperativt skall (B28) rundt den rene beslutningen: ruter til riktig policy, lar policyen hente
 * sitt eget grunnlag (I/O) og fatter beslutning for én hendelse.
 *
 * Bevisst UTENFOR denne slicen (utsatt til #19-fundamentet finnes):
 * - poll-løkka mot `inbox_hendelse` (`FOR UPDATE SKIP LOCKED`),
 * - effektuering: skriving av leveranse-rad(er) + `inbox_hendelse.status` i én DB-tx,
 * - retry/backoff ved transient grunnlags-I/O-feil.
 * Decisionsutfallet ([Decision]) er nettopp dataen den effektueringen skal skrive.
 */
class DecisionProcess internal constructor(
    private val isAliveDecisionPolicy: IsAliveDecisionPolicy,
    private val unrestrictedDecisionPolicy: UnrestrictedDecisionPolicy,
) {
    suspend fun process(event: Dispatch): Decision =
        when (val content = event.content) {
            is MicrofrontendEnable,
            is MicrofrontendDisable,
            -> decideWithPolicy(event.reference, content, unrestrictedDecisionPolicy)
            is BrukervarselCreate,
            is BrukervarselInactivate,
            is LedervarselCreate,
            is LedervarselInactivate,
            is DittSykefravaerCreate,
            is DittSykefravaerInactivate,
            is ArbeidsgivervarselCreate,
            is ArbeidsgivervarselInactivate,
            is BrevCreate,
            -> decideWithPolicy(event.reference, content, isAliveDecisionPolicy)
        }

    private suspend fun <T : DispatchContent, F> decideWithPolicy(
        reference: String,
        content: T,
        policy: DecisionPolicy<T, F>,
    ): Decision = policy.decide(reference, content, policy.fetchFoundation(content))
}
