package no.nav.budstikka.infrastructure.database.dispatch

import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.MdcKeys
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class InboxMessageRepositoryImpl(
    private val database: Database,
) : InboxMessageRepository {
    private val logger = LoggerFactory.getLogger(InboxMessageRepositoryImpl::class.java)

    override suspend fun saveBatch(messages: List<InboxMessage>) {
        if (messages.isEmpty()) {
            return
        }
        database.transact {
            val now = Clock.System.now()
            InboxMessageTable.batchInsert(messages, ignore = true) { message ->
                this[InboxMessageTable.eventId] = message.eventId
                this[InboxMessageTable.content] = message.content
                this[InboxMessageTable.reference] = message.reference
                this[InboxMessageTable.receivedAt] = now
            }
        }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
    ): List<InboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            val now = Clock.System.now()
            failPoisonRows(now, maxAttempts)
            val claimedRows =
                InboxMessageTable
                    .select(
                        InboxMessageTable.eventId,
                        InboxMessageTable.reference,
                        InboxMessageTable.content,
                        InboxMessageTable.state,
                    ).where {
                        (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                            claimExpired(now, maxAttempts) or waitExpired(now)
                    }.orderBy(
                        InboxMessageTable.receivedAt to SortOrder.ASC,
                        InboxMessageTable.eventId to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        ClaimedRow(
                            wasWaiting = row[InboxMessageTable.state] == InboxMessageState.WAIT.name,
                            message =
                                InboxMessage(
                                    eventId = row[InboxMessageTable.eventId],
                                    reference = row[InboxMessageTable.reference],
                                    content = row[InboxMessageTable.content],
                                ),
                        )
                    }
            markClaimed(claimedRows, now, lease)
            claimedRows.map { it.message }
        }
    }

    /**
     * Marks the claimed rows CLAIMED with a fresh [lease]. Waking a WAIT row is a scheduled resume
     * (the sending window opened), not a failed attempt, so it must NOT consume the attempt budget
     * (ADR 0014): otherwise repeated sending-window holds would count against [maxAttempts] and could
     * poison-FAIL a legitimately waiting message. Fresh RECEIVED rows and expired CLAIMED rows still
     * increment `attempt` as before.
     */
    private fun markClaimed(
        rows: List<ClaimedRow>,
        now: Instant,
        lease: Duration,
    ) {
        val wakingWaitIds = rows.filter { it.wasWaiting }.map { it.message.eventId }
        val attemptingIds = rows.filterNot { it.wasWaiting }.map { it.message.eventId }
        if (attemptingIds.isNotEmpty()) {
            InboxMessageTable.update({ InboxMessageTable.eventId inList attemptingIds }) {
                it[state] = InboxMessageState.CLAIMED.name
                it[nextAttemptTime] = now + lease
                it[attempt] = attempt + 1
            }
        }
        if (wakingWaitIds.isNotEmpty()) {
            InboxMessageTable.update({ InboxMessageTable.eventId inList wakingWaitIds }) {
                it[state] = InboxMessageState.CLAIMED.name
                it[nextAttemptTime] = now + lease
            }
        }
    }

    private data class ClaimedRow(
        val wasWaiting: Boolean,
        val message: InboxMessage,
    )

    private fun waitExpired(now: Instant): Op<Boolean> =
        (InboxMessageTable.state eq InboxMessageState.WAIT.name) and
            (InboxMessageTable.nextAttemptTime lessEq now)

    private fun claimExpired(
        now: Instant,
        maxAttempts: Int,
    ): Op<Boolean> =
        (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
            (InboxMessageTable.nextAttemptTime lessEq now) and
            (InboxMessageTable.attempt less maxAttempts)

    /**
     * Terminal gate for poison rows (#71): expired CLAIMED rows claimed [maxAttempts] times without
     * reaching terminal status become FAILED. Runs in the same transaction as claim, so a
     * deterministic failing row stops being reclaimed and cannot block the queue head (`receivedAt ASC`).
     *
     * Poison rows use `FOR UPDATE SKIP LOCKED` (like the claim), so concurrent replicas terminate
     * disjoint rows without blocking each other (ADR 0004, no leader).
     */
    private fun failPoisonRows(
        now: Instant,
        maxAttempts: Int,
    ) {
        val poisonIds =
            InboxMessageTable
                .select(InboxMessageTable.eventId)
                .where {
                    (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                        (InboxMessageTable.nextAttemptTime lessEq now) and
                        (InboxMessageTable.attempt greaterEq maxAttempts)
                }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { it[InboxMessageTable.eventId] }
        if (poisonIds.isEmpty()) {
            return
        }
        InboxMessageTable.update({ InboxMessageTable.eventId inList poisonIds }) {
            it[state] = InboxMessageState.FAILED.name
            it[nextAttemptTime] = null
            it[processedAt] = now
            it[waitReason] = null
            it[errorMessage] = "Poison row failed after reaching $maxAttempts attempts"
        }
        logger.warn(
            "Failed poison inbox message(s) after reaching max attempts {} {}",
            kv(MdcKeys.POISON_COUNT, poisonIds.size),
            kv(MdcKeys.MAX_ATTEMPTS, maxAttempts),
        )
    }

    override fun markProcessedInTransaction(eventId: UUID): Boolean =
        terminate(eventId, state = InboxMessageState.PROCESSED, dropReason = null, errorMessage = null)

    override fun markDroppedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.DROPPED, dropReason = reason, errorMessage = null)

    override fun markFailedInTransaction(
        eventId: UUID,
        reason: String,
    ): Boolean = terminate(eventId, state = InboxMessageState.FAILED, dropReason = null, errorMessage = reason)

    override fun markOutsideSendingWindowInTransaction(
        eventId: UUID,
        reason: String,
        nextRetry: Instant,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq eventId) and (InboxMessageTable.state eq InboxMessageState.CLAIMED.name)
        }) {
            it[InboxMessageTable.state] = InboxMessageState.WAIT.name
            it[InboxMessageTable.waitReason] = reason
            it[InboxMessageTable.nextAttemptTime] = nextRetry
        } > 0

    private fun terminate(
        eventId: UUID,
        state: InboxMessageState,
        dropReason: String?,
        errorMessage: String?,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq eventId) and (InboxMessageTable.state eq InboxMessageState.CLAIMED.name)
        }) {
            it[InboxMessageTable.state] = state.name
            it[InboxMessageTable.dropReason] = dropReason
            it[InboxMessageTable.errorMessage] = errorMessage
            it[InboxMessageTable.waitReason] = null
            it[processedAt] = Clock.System.now()
        } > 0
}
