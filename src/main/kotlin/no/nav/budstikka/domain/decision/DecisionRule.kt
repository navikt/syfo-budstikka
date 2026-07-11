package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch

/**
 * Én komponerbar regel i beslutningen (B28, komponerbare gater – B55). Regelen er delt i to
 * faser for å skille I/O fra ren logikk og for å la [DecisionProcess] hente grunnlaget for alle
 * gater KONKURRENT:
 *
 * 1. [resolve] (imperativt skall, I/O): slår opp det gaten trenger (PDL/KRR/NL) ut fra den
 *    immutable [Dispatch] og binder det inn i en ren [ResolvedRule]. Kjøres samtidig for alle gater.
 * 2. [ResolvedRule.apply] (ren kjerne): bruker regelen på gjeldende leveranser. Kjøres SEKVENSIELT
 *    slik at en gate som endrer kanal/leveranser ses av de neste.
 *
 * En gate som ikke gjelder for hendelsen returnerer en [ResolvedRule] som slipper leveransene
 * uendret gjennom (self-selection erstatter sentral ruting).
 */
internal fun interface DecisionRule {
    suspend fun resolve(event: Dispatch): ResolvedRule
}

/**
 * Den rene, grunnlags-bundne halvdelen av en [DecisionRule]. Transformerer leveransene (kan endre
 * kanal eller utvide listen), eller avbryter med [Decision.Dropped]/[Decision.Failed] som
 * short-circuiter resten av kjeden.
 */
internal fun interface ResolvedRule {
    fun apply(deliveries: List<DeliveryDraft>): Decision
}
