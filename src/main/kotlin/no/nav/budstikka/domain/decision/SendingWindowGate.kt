package no.nav.budstikka.domain.decision

import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.domain.foundation.BudstikkaSendingWindowLookup
import kotlin.time.Clock

internal class SendingWindowGate(
    private val clock: Clock = Clock.System,
) : DecisionRule {
    override suspend fun resolve(event: Dispatch): ResolvedRule {
        val now = clock.now()
        val gatedSendingWindow =
            event.content
                .gatedSendingWindow()
                ?.takeIf { it == SendingWindow.BUDSTIKKA_OPENING_HOURS }
                ?.let { BudstikkaSendingWindowLookup.isClosed(now) }
                ?: false

        return ResolvedRule { deliveries ->
            if (gatedSendingWindow) {
                val nextRetry = BudstikkaSendingWindowLookup.nextOpen(now)
                val reason = BudstikkaSendingWindowLookup.reason(now).first().reason
                Decision.NotInSendingWindow(nextRetry, reason)
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}
