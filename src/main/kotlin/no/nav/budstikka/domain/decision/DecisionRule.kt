package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Dispatch

/**
 * Resolves any required input and binds it into a pure [ResolvedRule]. [DecisionProcess] resolves
 * all rules concurrently, so each rule must avoid lookups when it does not apply to the event.
 */
internal fun interface DecisionRule {
    suspend fun resolve(event: Dispatch): ResolvedRule
}

/** Pure rule application over the deliveries produced so far. */
internal fun interface ResolvedRule {
    fun apply(deliveries: List<DeliveryDraft>): Decision
}
