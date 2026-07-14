package no.nav.budstikka.domain.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * Imperativt skall (B28) rundt de rene beslutningsgatene: seeder leveranse-utkastet fra hendelsen,
 * henter grunnlaget for alle [DecisionRule]-gater KONKURRENT ([DecisionRule.resolve] i `async`), og
 * folder deretter de rene [ResolvedRule]-avgjørelsene SEKVENSIELT over leveransene. Første ikke-
 * [Decision.Processed]-utfall (Dropped/Failed) short-circuiter resten (komponerbare gater, B55).
 *
 * Rekkefølgen i [rules] er anvendelses-rekkefølgen til den rene folden, og påvirker IKKE fetch/latens:
 * all grunnlagsinnhenting kjøres parallelt og short-circuites aldri av et tidlig dropp (et samtidig
 * oppslag kan altså være forgjeves – bevisst avveining). Rekkefølgen betyr kun at en gate som
 * transformerer leveransene må stå før gater som leser transformasjonen, og at den første gaten som
 * avbryter er den som bestemmer utfallet ([Decision.Dropped]/[Decision.Failed]).
 *
 * Bevisst UTENFOR denne slicen (utsatt til #19-fundamentet finnes):
 * - poll-løkka mot `inbox_hendelse` (`FOR UPDATE SKIP LOCKED`),
 * - effektuering: skriving av leveranse-rad(er) + `inbox_hendelse.status` i én DB-tx,
 * - retry/backoff ved transient grunnlags-I/O-feil.
 * Decisionsutfallet ([Decision]) er nettopp dataen den effektueringen skal skrive.
 */
class DecisionProcess internal constructor(
    private val rules: List<DecisionRule>,
) {
    suspend fun process(event: Dispatch): Decision =
        coroutineScope {
            val resolved = rules.map { async { it.resolve(event) } }.awaitAll()
            val seed: Decision = Decision.Processed(listOf(event.content.toDeliveryDraft(event.reference)))
            // Fold the pure decisions sequentially: as long as we're still Processed, apply the next
            // gate; a non-Processed outcome (Dropped/Failed) is carried through unchanged, so the
            // remaining gates are effectively short-circuited (B55).
            resolved.fold(seed) { decision, rule ->
                when (decision) {
                    is Decision.Processed -> rule.apply(decision.deliveries)
                    else -> decision
                }
            }
        }
}
