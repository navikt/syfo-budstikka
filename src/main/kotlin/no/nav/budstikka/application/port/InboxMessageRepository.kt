package no.nav.budstikka.application.port

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.PersonIdentifier
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Instant

/** Hydrated inbox row with [eventId] from the Kafka header and payload fields parsed at ingest. */
data class InboxMessage(
    val eventId: UUID,
    val reference: String,
    val content: DispatchContent,
)

data class ClaimedInboxMessage(
    val message: InboxMessage,
    val claimToken: ClaimToken,
) {
    val claim: InboxClaim get() = InboxClaim(message.eventId, claimToken)
}

data class InboxClaim(
    val eventId: UUID,
    val token: ClaimToken,
)

/** Min side identifies a microfrontend per person; the last command for this key wins (ADR 0018). */
data class MicrofrontendKey(
    val personIdentifier: PersonIdentifier,
    val microfrontendId: String,
) {
    /** Omits both parts: the person identifier and the surface shown to the person. */
    override fun toString(): String = "MicrofrontendKey()"
}

/**
 * A microfrontend command as seen under its key lock. A scheduled disable takes its source command's
 * place in ingest order, so [receivedAt] is the source's for a derived row.
 */
data class MicrofrontendCommand(
    val receivedAt: Instant,
    /** Pending scheduled disables derived from commands earlier in ingest order, locked for cancellation. */
    val earlierScheduledDisables: List<UUID>,
    /** A producer command for the same key, later in ingest order, has already been decided. */
    val laterCommandDecided: Boolean,
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
    ): List<ClaimedInboxMessage>

    /**
     * Authorises one processing attempt for a claimed row and spends it, atomically. Returns `false`
     * when the row is no longer CLAIMED under this caller's [claim] (a peer terminated or
     * reclaimed it) or has already spent [maxAttempts]; the caller must then skip the message and
     * leave it to the poison gate.
     *
     * `attempt` counts durable authorisations to START processing, not proven external effects.
     * Callers therefore invoke this BEFORE the first fallible, message-specific work, so a crash,
     * timeout or exception mid-processing still spends an attempt. The guard lives in the same
     * `UPDATE` as the increment, so a read-then-update race cannot exceed [maxAttempts].
     */
    suspend fun beginAttempt(
        claim: InboxClaim,
        maxAttempts: Int,
    ): Boolean

    /**
     * Decision transitions for the decision worker. They do NOT open their own transaction: they run
     * inside [TransactionRunner.transaction] with delivery writes, so one message is effectuated all
     * or nothing. The transition applies only while the row is CLAIMED and is idempotent for
     * rows already moved away from CLAIMED: those return `false`. Processed, dropped and failed are
     * terminal, while a message outside its sending window moves to WAIT. Each transition applies
     * only while CLAIMED under the caller's claim; a worker whose lease was reclaimed gets `false`.
     */
    fun markProcessedInTransaction(claim: InboxClaim): Boolean

    fun markDroppedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean

    fun markFailedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean

    fun markOutsideSendingWindowInTransaction(
        claim: InboxClaim,
        reason: String,
        nextRetry: Instant,
    ): Boolean

    /**
     * Serialises microfrontend decisions for [key] with a transaction-scoped advisory lock, then reads
     * the row [eventId] in ingest order `(received_at, event_id)` against the other rows for the key.
     * Call it before any other row lock in the transaction. Retention never takes the key lock and only
     * tries its own, so it cannot wait on this one.
     *
     * Row locks are taken in one statement ordered by `(received_at, event_id)`: the row [eventId] and
     * every pending scheduled disable (WAIT, or CLAIMED by a worker that has not decided it yet) derived
     * from an earlier command. Retention locks its candidates in the same order, so the two cannot form
     * a lock cycle. Returns `null` when the row no longer exists.
     */
    fun lockMicrofrontendCommandInTransaction(
        eventId: UUID,
        key: MicrofrontendKey,
    ): MicrofrontendCommand?

    /**
     * Cancels rows that are not decided yet (RECEIVED, CLAIMED or WAIT) by marking them PROCESSED
     * without materialising deliveries: the convention FERDIGSTILL uses for pending OPPRETT (#273).
     * A cancelled CLAIMED row loses its claim, so its worker's compare-and-set fails. The caller must
     * hold the rows' locks. Rows that are already terminal are left as they are. Returns the number of
     * cancelled rows.
     */
    fun cancelPendingInTransaction(eventIds: List<UUID>): Int

    /**
     * Inserts [message] as a row derived from [sourceEventId] that waits in WAIT until [notBefore].
     * The row inherits the source's `received_at`, which is both its place in ingest order and the
     * start of its retention clock. Ignored when [InboxMessage.eventId] already exists, so a repeated
     * decision cannot schedule twice. Returns whether a row was inserted.
     */
    fun saveDerivedInTransaction(
        sourceEventId: UUID,
        message: InboxMessage,
        waitReason: String,
        notBefore: Instant,
    ): Boolean
}
