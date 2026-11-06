package no.nav.budstikka.application.inbox

import kotlinx.coroutines.CancellationException
import no.nav.budstikka.application.delivery.DeliveryMetrics
import no.nav.budstikka.application.delivery.NarmesteLederLookup
import no.nav.budstikka.application.delivery.NarmesteLederMissingReason
import no.nav.budstikka.application.delivery.PersonNameLookup
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverRecipient
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.KalenderavtaleCreate
import no.nav.budstikka.contract.KalenderavtaleUpdate
import no.nav.budstikka.contract.NarmesteLeder
import no.nav.budstikka.contract.Orgnummer
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
                val requirements = draft.content.lookupRequirements() ?: return@map draft
                val leader =
                    (requirements.recipient as? NarmesteLeder)?.takeIf { requirements.requireLeader }?.let { recipient ->
                        val relation =
                            safeDecisionLookup { leaders.findActive(recipient.sykmeldt, requirements.orgnummer) }
                                ?: return missing(NarmesteLederMissingReason.MISSING_ACTIVE_LEADER)
                        if (recipient.externalVarsling != null && relation.epostadresser.isEmpty()) {
                            return missing(NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS)
                        }
                        relation.copy(epostadresser = relation.epostadresser.toList())
                    }
                val sykmeldt =
                    when (val recipient = requirements.recipient) {
                        is NarmesteLeder -> recipient.sykmeldt
                        is AltinnResource -> recipient.sykmeldt
                    }
                val name =
                    if (requirements.requireName && sykmeldt != null) {
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

private data class LookupRequirements(
    val recipient: ArbeidsgiverRecipient,
    val orgnummer: Orgnummer,
    val requireLeader: Boolean,
    val requireName: Boolean,
)

private fun DispatchContent.lookupRequirements(): LookupRequirements? =
    when (this) {
        is ArbeidsgivervarselCreate -> LookupRequirements(recipient, orgnummer, requireLeader = true, requireName = sakstype != null)
        is KalenderavtaleCreate ->
            (recipient as? NarmesteLeder)?.let {
                LookupRequirements(it, orgnummer, requireLeader = true, requireName = true)
            }
        is KalenderavtaleUpdate ->
            (recipient as? NarmesteLeder)?.let {
                LookupRequirements(
                    it,
                    orgnummer,
                    requireLeader = it.externalVarsling != null || saksstatus != null,
                    requireName = saksstatus != null,
                )
            }
        else -> null
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
