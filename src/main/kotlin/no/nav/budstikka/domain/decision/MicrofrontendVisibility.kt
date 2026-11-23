package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import kotlin.time.Clock
import kotlin.time.Instant

/** Wait reason of the disable an enable schedules for its `visibleUntil`. */
internal const val SCHEDULED_DISABLE_WAIT_REASON = "SCHEDULED_DISABLE"

/**
 * An enable whose `visibleUntil` has already passed is decided as an immediate disable. A
 * `visibleUntil` in the future becomes a [scheduledDispatch] instead.
 */
internal class PassedVisibleUntilRule(
    private val clock: Clock = Clock.System,
) : DecisionRule {
    override suspend fun resolve(event: Dispatch): ResolvedRule {
        val now = clock.now()
        return ResolvedRule { deliveries ->
            Decision.Processed(deliveries.map { it.disabledWhenVisibilityPassed(now) })
        }
    }
}

/**
 * The disable an enable with `visibleUntil` schedules for that time. The enable also has an
 * [orderingKey], so a newer command for the same microfrontend can cancel the plan.
 */
internal fun Decision.Processed.scheduledDispatch(): ScheduledDispatch? {
    val draft = deliveries.firstOrNull { it.content is MicrofrontendEnable } ?: return null
    val enable = draft.content as MicrofrontendEnable
    val visibleUntil = enable.visibleUntil ?: return null
    return ScheduledDispatch(
        reference = draft.reference,
        content = MicrofrontendDisable(enable.personIdentifier, enable.microfrontendId),
        notBefore = visibleUntil,
        waitReason = SCHEDULED_DISABLE_WAIT_REASON,
    )
}

private fun DeliveryDraft.disabledWhenVisibilityPassed(now: Instant): DeliveryDraft {
    val enable = content as? MicrofrontendEnable ?: return this
    val visibleUntil = enable.visibleUntil ?: return this
    if (visibleUntil > now) {
        return this
    }
    return copy(
        operation = Operation.INACTIVATE,
        content = MicrofrontendDisable(enable.personIdentifier, enable.microfrontendId),
    )
}
