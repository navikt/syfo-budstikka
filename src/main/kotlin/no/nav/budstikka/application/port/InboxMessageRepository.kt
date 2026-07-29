package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.DispatchContent
import java.util.UUID
import kotlin.time.Duration

/** Hydrated inbox row (ADR 0008): [eventId] from the Kafka header; [reference]/[content] parsed at ingest. */
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
     * blocking each other (competing consumers, no leader: ADR 0004). Also picks up CLAIMED rows when
     * their lease expires (crash recovery). Rows remain invisible to other pollers until lease expiry
     * or effectuation.
     *
     * Claiming does NOT spend an attempt: a claimed row that is never processed (batch abort, spent
     * lease budget, crash) must not approach the poison gate. [beginAttempt] spends the attempt.
     *
     * Poison gate (#71): a row that started processing [maxAttempts] times without reaching a
     * terminal state becomes FAILED instead of being reclaimed again, so a deterministic failing row
     * cannot block the queue head (`receivedAt ASC`) forever.
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
     * Terminal transitions for the decision worker. They do NOT open their own transaction: they run
     * inside [TransactionRunner.transaction] with delivery writes, so one message is effectuated all
     * or nothing (#56). The transition applies only while the row is CLAIMED and is idempotent for
     * terminal rows: an already terminal row returns `false`. Claims have no owner or fencing token,
     * so this compare-and-set does not distinguish a stale worker from a later reclaimer.
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
}
