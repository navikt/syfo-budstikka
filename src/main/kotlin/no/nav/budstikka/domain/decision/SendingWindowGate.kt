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
 * When the content's sending window is closed at [now], when it opens again and why it is closed;
 * `null` when the content may be sent now. Both the decision gate and the delivery worker use this,
 * and the exhaustive `when` makes every new [SendingWindow] value choose its rule explicitly.
 */
internal fun DispatchContent.closedSendingWindow(now: Instant): ClosedSendingWindow? =
    when (gatedSendingWindow()) {
        null, SendingWindow.ONGOING -> null
        SendingWindow.BUDSTIKKA_OPENING_HOURS ->
            if (BudstikkaSendingWindowLookup.isClosed(now)) {
                ClosedSendingWindow(
                    opensAt = BudstikkaSendingWindowLookup.nextOpen(now),
                    reason = BudstikkaSendingWindowLookup.reason(now).first().reason,
                )
            } else {
                null
            }
    }
