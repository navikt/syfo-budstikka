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
     * Poison gate (#71): a row claimed [maxAttempts] times without a terminal state becomes FAILED
     * instead of being reclaimed again, so a deterministic failing row cannot block the queue head
     * (`receivedAt ASC`) forever.
     */
    suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage>

    /**
     * Terminal transitions for the decision worker. They do NOT open their own transaction: they run
     * inside [TransactionRunner.transaction] with delivery writes, so one message is effectuated all
     * or nothing (#56). The transition applies only from CLAIMED (idempotent compare-and-set: an
     * already terminal or reclaimed message returns `false`, so a loser in a lease race writes no deliveries).
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
