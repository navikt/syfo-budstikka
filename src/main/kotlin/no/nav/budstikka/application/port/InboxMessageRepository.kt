package no.nav.budstikka.application.port

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.domain.decision.FerdigstillMatch
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
     * instead of being reclaimed forever. WAIT rows are scheduled resumes (sending-window holds or
     * technical FERDIGSTILL rechecks), so waking them also leaves the attempt budget untouched.
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
     * or nothing. The transition applies only while the row is CLAIMED and is idempotent for
     * terminal rows: an already terminal row returns `false`. Claims have no owner or fencing token,
     * so this compare-and-set does not distinguish a stale worker from a later reclaimer.
     */
    fun markProcessedInTransaction(eventId: UUID): Boolean

    /**
     * Acquires a PostgreSQL row lock for a currently claimed inbox row. Effectuation holds this lock
     * through its terminal transition and any delivery write so a FERDIGSTILL cancellation cannot
     * race a waking CREATE into materializing a delivery.
     */
    fun lockClaimedForEffectuationInTransaction(eventId: UUID): Boolean

    /**
     * Locks every matching CREATE that is either waiting for the sending window or has been woken
     * but still carries its wait reason. The caller must re-check materialized deliveries after
     * this call because a CREATE effectuation may have won while this transaction waited for a
     * lock.
     */
    fun lockWaitingCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID>

    /** Marks a locked WAIT/awakened-WAIT CREATE as terminal without materializing a delivery. */
    fun markWaitingCreateProcessedInTransaction(eventId: UUID): Boolean

    /**
     * Holds a claimed FERDIGSTILL row in WAIT until the matching CREATE delivery reaches SENT. This
     * reuses the same WAIT semantics as sending-window holds, including resetting the spent attempt
     * budget.
     */
    fun markWaitingForCreateSentInTransaction(
        eventId: UUID,
        nextRetry: Instant,
    ): Boolean

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
