package no.nav.budstikka.application.port

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import java.util.UUID
import kotlin.time.Duration

data class ClaimedDelivery(
    val id: UUID,
    val inboxEventId: UUID?,
    val reference: String,
    val channel: Channel,
    val payload: DispatchContent,
    val operation: Operation = Operation.CREATE,
    val createExternalId: String? = null,
)

data class StoredCreateDelivery(
    val createExternalId: String?,
    val reference: String,
    val channel: Channel,
    val recipient: Recipient,
    val payload: DispatchContent,
    val state: StoredCreateDeliveryState,
)

/**
 * Application-owned projection of the persisted CREATE delivery state needed for FERDIGSTILL
 * matching. Keeps the effectuation logic decoupled from infrastructure enums and table details.
 */
enum class StoredCreateDeliveryState {
    READY,
    CLAIMED,
    SENT,
    FAILED,
}

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

    /** Reads the latest matching CREATE row. The caller owns serialization through the source inbox lock. */
    fun findCreateForFerdigstillInTransaction(match: FerdigstillMatch): StoredCreateDelivery?

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
