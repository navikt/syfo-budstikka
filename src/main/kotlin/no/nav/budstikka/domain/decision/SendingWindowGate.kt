package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.SendingWindow
import no.nav.budstikka.domain.foundation.BudstikkaSendingWindow
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
                ?.let { !BudstikkaSendingWindow.isOpen(now) }
                ?: false

        return ResolvedRule { deliveries ->
            if (gatedSendingWindow) {
                val nextRetry = BudstikkaSendingWindow.nextOpen(now)
                Decision.NotInSendingWindow(nextRetry)
            } else {
                Decision.Processed(deliveries)
            }
        }
    }
}
