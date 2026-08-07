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
            val claimed =
                InboxMessageTable
                    .select(
                        InboxMessageTable.eventId,
                        InboxMessageTable.reference,
                        InboxMessageTable.content,
                    ).where {
                        (InboxMessageTable.state eq InboxMessageState.RECEIVED.name) or
                            claimExpired(now, maxAttempts) or waitExpired(now)
                    }.orderBy(
                        InboxMessageTable.receivedAt to SortOrder.ASC,
                        InboxMessageTable.eventId to SortOrder.ASC,
                    ).limit(limit)
                    .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                    .map { row ->
                        InboxMessage(
                            eventId = row[InboxMessageTable.eventId],
                            reference = row[InboxMessageTable.reference],
                            content = row[InboxMessageTable.content],
                        )
                    }
            if (claimed.isNotEmpty()) {
                InboxMessageTable.update({ InboxMessageTable.eventId inList claimed.map { it.eventId } }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[nextAttemptTime] = now + lease
                }
            }
            claimed
        }
    }

    /**
     * Spends one processing attempt, guarded in the same statement so concurrent replicas cannot
     * push `attempt` past [maxAttempts]. Claiming deliberately does not touch `attempt`: a row that
     * is claimed but never processed (batch abort, spent lease budget, crash) must keep its budget,
     * and waking a WAIT row is a scheduled resume, not a new failure.
     */
    override suspend fun beginAttempt(
        eventId: UUID,
        maxAttempts: Int,
    ): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            InboxMessageTable.update({
                (InboxMessageTable.eventId eq eventId) and
                    (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                    (InboxMessageTable.attempt less maxAttempts)
            }) {
                it[attempt] = attempt + 1
            } > 0
        }
    }

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
     * Expired CLAIMED rows that already spent [maxAttempts] processing attempts become FAILED. Runs
     * in the same transaction as claim, so a deterministic failing row stops being reclaimed and
     * cannot block the queue head (`receivedAt ASC`).
     *
     * Because `attempt` is spent by [beginAttempt] and not by claiming, a row that was claimed but
     * never processed keeps its budget and is not terminated here.
     *
     * Poison rows use `FOR UPDATE SKIP LOCKED` (like the claim), so concurrent replicas terminate
     * distinct rows without blocking each other.
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
            // Reaching a hold decision is a successful evaluation, not a failure. The attempt spent
            // by beginAttempt is handed back, so repeated sending-window holds can never push a
            // legitimately waiting message into the poison gate.
            it[attempt] = 0
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
