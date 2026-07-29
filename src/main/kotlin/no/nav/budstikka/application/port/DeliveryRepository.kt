package no.nav.budstikka.application.port

import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.dispatch.DispatchContent
import java.util.UUID
import kotlin.time.Duration

data class ClaimedDelivery(
    val id: UUID,
    val inboxEventId: UUID?,
    val reference: String,
    val channel: Channel,
    val payload: DispatchContent,
)

/**
 * Writes frozen [DeliveryDraft] values as `delivery` rows. One inbox event yields 0..N deliveries.
 * Does NOT open its own transaction: it runs inside [TransactionRunner.transaction] together with
 * the inbox state transition, so the decision worker (#56) effectuates one message all or nothing.
 * Database defaults populate `id`/`state`/`attempt` (uuidv7 / 'READY' / 0).
 */
interface DeliveryRepository {
    fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    )

    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): List<ClaimedDelivery>

    /**
     * Authorises one delivery attempt for a claimed row and spends it, atomically. Returns `false`
     * when the row is no longer CLAIMED (a peer terminated it) or has already spent [maxAttempts];
     * the caller must then skip the row and leave it to the poison gate.
     *
     * `attempt` counts durable authorisations to START a delivery, not proven external sends.
     * Callers therefore invoke this BEFORE handing the row to a channel handler, so a crash or
     * timeout mid-send still spends an attempt. The guard lives in the same `UPDATE` as the
     * increment, so a read-then-update race cannot exceed [maxAttempts].
     */
    suspend fun beginAttempt(
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean

    suspend fun markSent(deliveryId: UUID): Boolean

    suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean
}
