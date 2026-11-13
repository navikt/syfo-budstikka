package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.domain.foundation.BudstikkaSendingWindowLookup
import kotlin.time.Clock
import kotlin.time.Instant

internal class SendingWindowGate(
    private val clock: Clock = Clock.System,
) : DecisionRule {
    override suspend fun resolve(event: Dispatch): ResolvedRule {
        val closed = event.content.closedSendingWindow(clock.now())

        return ResolvedRule { deliveries ->
            if (closed != null) {
                Decision.NotInSendingWindow(closed.opensAt, closed.reason)
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}

internal data class ClosedSendingWindow(
    val opensAt: Instant,
    val reason: String,
)

/**
 * When and why the content's sending window is closed at [now], or `null` when it may be sent now.
 * The exhaustive `when` forces every new [SendingWindow] value to choose its rule.
 */
internal fun DispatchContent.closedSendingWindow(now: Instant): ClosedSendingWindow? = gatedSendingWindow()?.closedAt(now)

private fun SendingWindow.closedAt(now: Instant): ClosedSendingWindow? =
    when (this) {
        SendingWindow.ONGOING -> null
        SendingWindow.BUDSTIKKA_OPENING_HOURS -> {
            val closed = BudstikkaSendingWindowLookup.reason(now).firstOrNull() ?: return null
            ClosedSendingWindow(BudstikkaSendingWindowLookup.nextOpen(now), closed.reason)
        }
    }
