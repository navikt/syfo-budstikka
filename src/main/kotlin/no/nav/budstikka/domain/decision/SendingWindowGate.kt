package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.SendingWindow
import no.nav.budstikka.domain.foundation.BudstikkaSendingWindowLookup
import kotlin.time.Clock

/**
 * SendingWindowGate: blokkerer utsending utenfor vårt bestemte tidsvindu.
 * Åpent mandag–lørdag 08:00–20:00 (Europe/Oslo), stengt søndager og norske helligdager + julaften og nyttårsaften.
 *
 * Altinn og dokdist har egne regler for utsending.
 */
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
                val reason = BudstikkaSendingWindowLookup.reason(now)
                Decision.NotInSendingWindow(nextRetry, reason.first().reason)
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}
