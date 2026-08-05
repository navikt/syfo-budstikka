package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.DispatchContent
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
     * A row claimed [maxAttempts] times without a terminal state becomes FAILED instead of being
     * reclaimed forever.
     */
    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage>

    /**
     * Terminal transitions for the decision worker. They do NOT open their own transaction: they run
     * inside [TransactionRunner.transaction] with delivery writes, so one message is effectuated all
     * or nothing. The transition applies only while the row is CLAIMED and is idempotent for
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

    fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean
}
