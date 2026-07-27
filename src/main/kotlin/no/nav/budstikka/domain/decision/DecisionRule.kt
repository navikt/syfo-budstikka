package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * One composable decision rule (B28, composable gates – B55). The rule has two phases to separate
 * I/O from pure logic and let [DecisionProcess] fetch inputs for all gates CONCURRENTLY:
 *
 * 1. [resolve] (imperative shell, I/O): looks up what the gate needs (PDL/KRR/NL) from immutable
 *    [Dispatch] and binds it into a pure [ResolvedRule]. Runs concurrently for all gates.
 * 2. [ResolvedRule.apply] (pure core): applies the rule to current deliveries. Runs SEQUENTIALLY so
 *    later gates observe a changed channel or delivery list.
 *
 * A gate that does not apply to the event returns a [ResolvedRule] that leaves deliveries unchanged
 * (self-selection replaces central routing).
 *
 * ## Adding a gate (example: looking up nærmeste leder)
 *
 * Every gate's [resolve] runs CONCURRENTLY ([DecisionProcess]), so [Decision.Dropped] from one gate
 * does not prevent another lookup: both have already started. Add a new lookup (such as nærmeste
 * leder) to avoid unnecessary calls through self-selection, not through early return:
 *
 * 1. **Port** (`domain/foundation`): define a domain-blind, I/O-free lookup interface; make it
 *    `suspend` so the adapter can call the network, mirroring [DeathLookup]. For example,
 *    `fun interface NearestLeaderLookup { suspend fun forPerson(ident: PersonIdentifier): Leder? }`.
 * 2. **Adapter** (`infrastructure/client`): implement the port against the real service (reuse the
 *    shared `HttpClient` and `TokenProvider`, not a dedicated client), then register it in
 *    `ClientModule`. In tests, replace it with an in-memory fake through overrides (B52).
 * 3. **Gate** (`domain/decision`): write a [DecisionRule] that receives the port. Look up only in
 *    [resolve] when the event can be gated (self-select with `gatedPerson()`, for example, so
 *    [DeathGate] calls PDL only when a gated person exists). This avoids a wasted call for irrelevant
 *    events. Bind the result into a pure [ResolvedRule] that transforms deliveries (changes channel
 *    or extends the list) or stops with [Decision.Dropped]/[Decision.Failed].
 * 4. **Wiring** (`bootstrap/WorkerModule`): add the gate to `listOf(...)` for `List<DecisionRule>`.
 *    Ordering affects ONLY the pure fold (not fetch latency: all [resolve] calls run in parallel):
 *    (a) a delivery-transforming gate must precede gates that read that transformation; and (b) when
 *    several gates would stop, the FIRST in the list short-circuits and its result is reported.
 */
internal fun interface DecisionRule {
    suspend fun resolve(event: Dispatch): ResolvedRule
}

/**
 * The pure, resolved half of a [DecisionRule]. It transforms deliveries (may change a channel or
 * extend the list), or returns [Decision.Dropped]/[Decision.Failed] to short-circuit the chain.
 */
internal fun interface ResolvedRule {
    fun apply(deliveries: List<DeliveryDraft>): Decision
}
