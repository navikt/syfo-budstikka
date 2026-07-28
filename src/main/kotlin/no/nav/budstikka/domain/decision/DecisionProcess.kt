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
 * This class owns only input resolution and the pure fold. `InboxMessageWorker` owns polling and
 * lease handling; `EffectuateDecision` writes delivery rows and `inbox_message.state` atomically.
 * Transient input-fetch failures propagate to the worker so the claimed row can be retried after
 * its lease expires.
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
