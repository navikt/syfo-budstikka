package no.nav.budstikka.application.port

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.dispatch.DispatchContent
import java.time.Duration
import java.util.UUID

data class ClaimedDelivery(
    val id: UUID,
    val inboxEventId: UUID?,
    val channel: Channel,
    val payload: DispatchContent,
)

/**
 * Skriver frosne [DeliveryDraft] som `delivery`-rader. Én inbox-hendelse gir 0..N leveranser.
 * Åpner IKKE egen transaksjon: kjøres inne i
 * [TransactionRunner.transaction] sammen med inbox-
 * status-overgangen, slik at beslutnings-workeren (#56) effektuerer én melding alt-eller-ingenting.
 * `id`/`state`/`attempt` fylles av DB-defaults (uuidv7 / 'READY' / 0).
 */
interface DeliveryRepository {
    fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    )

    suspend fun claim(
        limit: Int,
        lease: Duration,
        channels: Set<Channel>,
    ): List<ClaimedDelivery>

    suspend fun markSent(deliveryId: UUID): Boolean

    suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean
}
