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
    val sourceCreateDeliveryId: UUID? = null,
)

data class StoredCreateDelivery(
    val id: UUID,
    val createExternalId: String?,
    val reference: String,
    val channel: Channel,
    val recipient: Recipient,
    val payload: DispatchContent,
)

data class DeliveryClaimResult(
    val deliveries: List<ClaimedDelivery>,
    val failedSourceDependencies: Int,
) : List<ClaimedDelivery> by deliveries

enum class SourceSendGuardResult {
    DISPATCHED,
    CONTENDED,
    SOURCE_NOT_ELIGIBLE,
}

/**
 * State transitions performed for one guarded dispatch. Implementations that use a session guard
 * keep these operations on the same checked-out connection as the guard.
 */
interface DeliveryAttempt {
    suspend fun beginAttempt(maxAttempts: Int): Boolean

    suspend fun markSent(): Boolean

    suspend fun markFailed(reason: String): Boolean
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

    /** Reads every matching CREATE row. The caller owns serialization through the source inbox lock. */
    fun findCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<StoredCreateDelivery>

    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): DeliveryClaimResult

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

    /**
     * Serializes dispatches belonging to one source CREATE delivery across replicas. Implementations
     * must re-check source eligibility after acquiring the guard and release it after [block]
     * finishes, including its terminal state transition.
     *
     * Returns why dispatch did not start when another sender owns the source guard or the source is
     * no longer eligible. In either case no attempt may be spent.
     */
    suspend fun withSourceSendGuard(
        delivery: ClaimedDelivery,
        block: suspend (DeliveryAttempt) -> Unit,
    ): SourceSendGuardResult
}
