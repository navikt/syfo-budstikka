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
 * Rekkefølgen i [rules] er anvendelses-rekkefølgen: sett billigst/mest droppende gate først. Parallell
 * grunnlagsinnhenting sparer latens når flere gater gjør nett-kall, men short-circuiter ikke selve
 * I/O-en – et tidlig dropp betyr at et samtidig oppslag var forgjeves (bevisst avveining).
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
            var deliveries = listOf(event.content.toDeliveryDraft(event.reference))
            for (rule in resolved) {
                when (val outcome = rule.apply(deliveries)) {
                    is Decision.Processed -> deliveries = outcome.deliveries
                    else -> return@coroutineScope outcome
                }
            }
            Decision.Processed(deliveries)
        }
}
