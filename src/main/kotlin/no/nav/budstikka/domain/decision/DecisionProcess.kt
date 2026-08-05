package no.nav.budstikka.domain.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * Resolves every rule concurrently, then applies the resolved rules sequentially. Rule order
 * affects transformations and which terminal decision wins, but does not prevent already-started
 * lookups.
 */
class DecisionProcess internal constructor(
    private val rules: List<DecisionRule>,
) {
    suspend fun process(event: Dispatch): Decision =
        coroutineScope {
            val resolved = rules.map { async { it.resolve(event) } }.awaitAll()
            val seed: Decision = Decision.Processed(listOf(event.content.toDeliveryDraft(event.reference)))
            resolved.fold(seed) { decision, rule ->
                when (decision) {
                    is Decision.Processed -> rule.apply(decision.deliveries)
                    else -> decision
                }
            }
        }
}
