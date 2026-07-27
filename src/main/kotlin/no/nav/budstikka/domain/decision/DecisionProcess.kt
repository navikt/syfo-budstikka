package no.nav.budstikka.domain.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * Imperative shell (B28) around pure decision gates: seeds the delivery draft from the event, fetches
 * inputs for every [DecisionRule] CONCURRENTLY ([DecisionRule.resolve] in `async`), then folds pure
 * [ResolvedRule] decisions SEQUENTIALLY over deliveries. The first non-[Decision.Processed] outcome
 * (Dropped/Failed) short-circuits the rest (composable gates, B55).
 *
 * Order in [rules] is the application order for the pure fold and does NOT affect fetch latency:
 * all input fetching runs in parallel and is never short-circuited by an early drop (a concurrent
 * lookup can therefore be unnecessary: a deliberate trade-off). Order only means a gate that
 * transforms deliveries must precede gates reading that transformation, and the first stopping gate
 * determines the outcome ([Decision.Dropped]/[Decision.Failed]).
 *
 * Deliberately OUTSIDE this slice (deferred until the #19 foundation exists):
 * - polling `inbox_hendelse` (`FOR UPDATE SKIP LOCKED`),
 * - effectuation: writing delivery row(s) and `inbox_hendelse.status` in one database transaction,
 * - retry/backoff for transient input-fetch I/O failures.
 * The decision result ([Decision]) is exactly the data that effectuation writes.
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
