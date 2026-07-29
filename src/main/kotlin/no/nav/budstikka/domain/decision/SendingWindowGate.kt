package no.nav.budstikka.domain.decision

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.foundation.BudstikkaSendingWindow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

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
        return if (BudstikkaSendingWindow.isOpen(now)) {
            ResolvedRule { deliveries -> Decision.Processed(deliveries) }
        } else {
            val nextOpen = BudstikkaSendingWindow.nextOpen(now)
            if (nextOpen != null) {
                ResolvedRule { _ -> Decision.NotInSendingWindow(nextOpen) }
            } else {
                ResolvedRule { _ -> Decision.NotInSendingWindow(now + 1.hours) }
            }
        }
    }
}
