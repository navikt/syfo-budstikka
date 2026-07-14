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
 *
 * ## Slik legger du til en ny gate (eksempel: hente nærmeste leder)
 *
 * Alle gaters [resolve] kjøres KONKURRENT ([DecisionProcess]), så et [Decision.Dropped] fra én gate
 * hindrer ikke oppslaget i en annen – de er allerede startet. Nytt-oppslag (f.eks. nærmeste leder)
 * legges derfor til slik at unødige kall unngås ved self-selection, ikke ved tidlig retur:
 *
 * 1. **Port** (`domain/foundation`): definer et domeneblindt, I/O-fritt grensesnitt for oppslaget,
 *    `suspend` slik at adapteren kan gjøre nett – speil [DeathLookup]. F.eks.
 *    `fun interface NearestLeaderLookup { suspend fun forPerson(ident: PersonIdentifier): Leder? }`.
 * 2. **Adapter** (`infrastructure/client`): implementer porten mot den ekte tjenesten (gjenbruk delt
 *    `HttpClient` + `TokenProvider`, ikke egen klient), og registrer den i `ClientModule`. I test
 *    byttes porten mot en in-memory fake via overrides (B52).
 * 3. **Gate** (`domain/decision`): skriv en [DecisionRule] som tar porten inn. Gjør oppslaget i
 *    [resolve] KUN når hendelsen faktisk kan gates (self-select på f.eks. `gatedPerson()`, slik
 *    [DeathGate] bare kaller PDL når det finnes en gated person) – da slipper irrelevante hendelser
 *    et forgjeves kall. Bind resultatet inn i en ren [ResolvedRule] som transformerer leveransene
 *    (endre kanal / utvide listen) eller avbryter med [Decision.Dropped]/[Decision.Failed].
 * 4. **Wiring** (`bootstrap/WorkerModule`): legg gaten til i `listOf(...)` for `List<DecisionRule>`.
 *    Rekkefølgen påvirker KUN den rene folden (ikke fetch/latens – alle [resolve] kjøres parallelt):
 *    (a) en gate som transformerer leveransene (endrer kanal / utvider listen) må stå FØR gater som
 *    leser den transformasjonen, og (b) når flere gater ville avbryte, er det den FØRSTE i lista som
 *    short-circuiter – dens [Decision.Dropped]/[Decision.Failed]-utfall er det som rapporteres.
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
