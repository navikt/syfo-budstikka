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
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.DeliverySnapshot

/**
 * Freezes the active Nærmeste leder and the Sykmeldt's name on each delivery as a
 * [DeliverySnapshot.Resolved], so delivery, retries and sak operations never ask the registers again.
 *
 * Runs as the last step of `DecisionProcess`, only for [Decision.Processed]. Messages that wait for the
 * sending window or are stopped by a gate cause no lookups.
 *
 * - No active leader, or no email address when external notification is ordered: [Decision.Failed].
 * - No name: valid. The sak then gets its general title.
 * - A register error throws, so the inbox lease expires and the message is retried.
 */
class ArbeidsgiverSnapshotResolver(
    private val leaders: NarmesteLederLookup,
    private val names: PersonNameLookup,
    private val metrics: DeliveryMetrics,
) {
    suspend fun resolve(decision: Decision.Processed): Decision {
        val resolved = mutableListOf<DeliveryDraft>()
        for (draft in decision.deliveries) {
            val lookups = draft.content.neededLookups()
            if (lookups == null) {
                resolved += draft
                continue
            }

            val leaderFor = lookups.leaderFor
            val leader =
                if (leaderFor == null) {
                    null
                } else {
                    val relation =
                        safeSnapshotLookup { leaders.findActive(leaderFor.sykmeldt, lookups.orgnummer) }
                            ?: return missing(NarmesteLederMissingReason.MISSING_ACTIVE_LEADER)
                    if (leaderFor.externalVarsling != null && relation.epostadresser.isEmpty()) {
                        return missing(NarmesteLederMissingReason.MISSING_EMAIL_ADDRESS)
                    }
                    relation.copy(epostadresser = relation.epostadresser.toList())
                }

            val nameOf = lookups.nameOf
            val name = if (nameOf == null) null else safeSnapshotLookup { names.findName(nameOf) }

            resolved += draft.copy(snapshot = DeliverySnapshot.Resolved(leader, name))
        }
        return Decision.Processed(resolved)
    }

    private fun missing(reason: NarmesteLederMissingReason): Decision.Failed {
        metrics.narmesteLederMissing(reason)
        return Decision.Failed(reason.name)
    }
}

/** What one delivery needs from the registers. A null field means that lookup is skipped. */
private class Lookups(
    val orgnummer: Orgnummer,
    val leaderFor: NarmesteLeder?,
    val nameOf: PersonIdentifier?,
)

/** Null means nothing is looked up, and the delivery is left as it is. */
private fun DispatchContent.neededLookups(): Lookups? =
    when (this) {
        is ArbeidsgivervarselCreate ->
            Lookups(
                orgnummer = orgnummer,
                leaderFor = recipient as? NarmesteLeder,
                // The name is only used for the title of a Budstikka-owned sak.
                nameOf = if (sakstype != null) recipient.sykmeldtOrNull() else null,
            )
        // Kalenderavtaler are only supported for a Nærmeste leder; other recipients fail downstream.
        is KalenderavtaleCreate ->
            (recipient as? NarmesteLeder)?.let { leader ->
                Lookups(orgnummer, leaderFor = leader, nameOf = leader.sykmeldt)
            }
        is KalenderavtaleUpdate ->
            (recipient as? NarmesteLeder)?.let { leader ->
                val setsSaksstatus = saksstatus != null
                Lookups(
                    orgnummer = orgnummer,
                    leaderFor = if (setsSaksstatus || leader.externalVarsling != null) leader else null,
                    nameOf = if (setsSaksstatus) leader.sykmeldt else null,
                )
            }
        else -> null
    }

private fun ArbeidsgiverRecipient.sykmeldtOrNull(): PersonIdentifier? =
    when (this) {
        is NarmesteLeder -> sykmeldt
        is AltinnResource -> sykmeldt
    }

internal suspend fun <T> safeSnapshotLookup(action: suspend () -> T): T =
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // Register clients can include personal data in their throwable chains.
        throw IllegalStateException("Snapshot lookup failed")
    }
