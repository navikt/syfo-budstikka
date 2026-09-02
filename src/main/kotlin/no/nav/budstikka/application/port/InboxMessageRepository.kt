package no.nav.budstikka.application.port

import no.nav.budstikka.contract.DispatchContent
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/** Hydrated inbox row with [eventId] from the Kafka header and payload fields parsed at ingest. */
data class InboxMessage(
    val eventId: UUID,
    val reference: String,
    val content: DispatchContent,
)

interface InboxMessageRepository {
    suspend fun saveBatch(messages: List<InboxMessage>)

    /**
     * Claims up to [limit] received messages for processing and marks them CLAIMED with a [lease] in
     * ONE transaction. Uses `FOR UPDATE SKIP LOCKED`, so replicas receive disjoint batches without
     * blocking each other. Also picks up CLAIMED rows when their lease expires. Rows remain invisible
     * to other pollers until lease expiry or effectuation.
     *
     * Claiming does NOT spend an attempt: a claimed row that is never processed (batch abort, spent
     * lease budget, crash) must not approach the poison gate. [beginAttempt] spends the attempt.
     * A row that started processing [maxAttempts] times without a terminal state becomes FAILED
     * instead of being reclaimed forever.
     */
    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage>

    /**
     * Authorises one processing attempt for a claimed row and spends it, atomically. Returns `false`
     * when the row is no longer CLAIMED (a peer terminated it) or has already spent [maxAttempts];
     * the caller must then skip the message and leave it to the poison gate.
     *
     * `attempt` counts durable authorisations to START processing, not proven external effects.
     * Callers therefore invoke this BEFORE the first fallible, message-specific work, so a crash,
     * timeout or exception mid-processing still spends an attempt. The guard lives in the same
     * `UPDATE` as the increment, so a read-then-update race cannot exceed [maxAttempts].
     */
    suspend fun beginAttempt(
        eventId: UUID,
        maxAttempts: Int,
    ): Boolean

    /**
     * Decision transitions for the decision worker. They do NOT open their own transaction: they run
     * inside [TransactionRunner.transaction] with delivery writes, so one message is effectuated all
     * or nothing. The transition applies only while the row is CLAIMED and is idempotent for
     * rows already moved away from CLAIMED: those return `false`. Processed, dropped and failed are
     * terminal, while a message outside its sending window moves to WAIT. Claims have no owner or
     * fencing token, so this compare-and-set does not distinguish a stale worker from a later reclaimer.
     */
    fun markProcessedInTransaction(eventId: UUID): Boolean

    fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean

    fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean

    fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean
}
