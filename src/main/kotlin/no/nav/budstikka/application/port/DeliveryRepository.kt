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
 * the inbox state transition, so one message is persisted all or nothing.
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

    suspend fun markSent(deliveryId: UUID): Boolean

    suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean
}
