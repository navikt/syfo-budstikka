package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.foundation.DeathLookup

internal data class IsAliveFoundation(
    val recipientIsDead: Boolean,
)

internal interface DecisionPolicy<T : DispatchContent, F> {
    suspend fun fetchFoundation(content: T): F

    fun decide(
        reference: String,
        content: T,
        foundation: F,
    ): Decision
}

internal class IsAliveDecisionPolicy(
    private val deathLookup: DeathLookup,
) : DecisionPolicy<DispatchContent, IsAliveFoundation> {
    override suspend fun fetchFoundation(content: DispatchContent): IsAliveFoundation {
        val recipientIsDead = content.gatedPerson()?.let { deathLookup.isDead(it) } ?: false
        return IsAliveFoundation(recipientIsDead)
    }

    override fun decide(
        reference: String,
        content: DispatchContent,
        foundation: IsAliveFoundation,
    ): Decision =
        if (foundation.recipientIsDead && content.gatedPerson() != null) {
            Decision.Dropped(DropReason.DEAD)
        } else {
            Decision.Processed(listOf(content.toDeliveryDraft(reference)))
        }
}

internal object UnrestrictedDecisionPolicy : DecisionPolicy<DispatchContent, Unit> {
    override suspend fun fetchFoundation(content: DispatchContent): Unit = Unit

    override fun decide(
        reference: String,
        content: DispatchContent,
        foundation: Unit,
    ): Decision = Decision.Processed(listOf(content.toDeliveryDraft(reference)))
}
