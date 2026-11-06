package no.nav.budstikka.application.inbox

import kotlinx.coroutines.CancellationException
import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliverySnapshot

/** Enriches only deliveries which passed every gate, before the atomic inbox/delivery write. */
class ArbeidsgiverDecisionEnrichment(
    private val leaders: NarmesteLederLookup,
    private val names: PersonNameLookup,
    private val metrics: DeliveryMetrics,
) {
    suspend fun enrich(decision: Decision.Processed): Decision {
        val drafts =
            decision.deliveries.map { draft ->
                val create = draft.content as? ArbeidsgivervarselCreate ?: return@map draft
                val leader =
                    (create.recipient as? NarmesteLeder)?.let { recipient ->
                        val relation =
                            safeDecisionLookup { leaders.findActive(recipient.sykmeldt, create.orgnummer) }
                                ?: return missing(NarmesteLederMissingReason.MISSING_ACTIVE_LEADER)
                        if (recipient.externalVarsling != null && relation.epostadresser.isEmpty()) {
                            return missing(NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS)
                        }
                        relation.copy(epostadresser = relation.epostadresser.toList())
                    }
                val sykmeldt =
                    when (val recipient = create.recipient) {
                        is NarmesteLeder -> recipient.sykmeldt
                        is AltinnResource -> recipient.sykmeldt
                    }
                val name =
                    if (create.sakstype != null && sykmeldt != null) {
                        safeDecisionLookup { names.findName(sykmeldt) }
                    } else {
                        null
                    }
                draft.copy(snapshot = DeliverySnapshot.Resolved(leader, name))
            }
        return Decision.Processed(drafts)
    }

    private fun missing(reason: NarmesteLederMissingReason): Decision.Failed {
        metrics.narmesteLederMissing(reason)
        return Decision.Failed(reason.name)
    }
}

internal suspend fun <T> safeDecisionLookup(action: suspend () -> T): T =
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Register clients can include personal data in their throwable chains.
        throw IllegalStateException("Decision enrichment lookup failed")
    }
