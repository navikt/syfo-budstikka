package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.application.logging.applicationLogger
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.application.port.ClaimedInboxMessage
import no.nav.budstikka.application.port.InboxClaim
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.MicrofrontendCommand
import no.nav.budstikka.application.port.MicrofrontendKey
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
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class PostgresInboxMessageRepository(
    private val database: Database,
) : InboxMessageRepository {
    private val logger = applicationLogger(PostgresInboxMessageRepository::class.java)

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
    ): List<ClaimedInboxMessage> {
        require(limit > 0) { "limit must be greater than 0" }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            val now = Clock.System.now()
            val claimToken = ClaimToken.generate()
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
                        ClaimedInboxMessage(
                            message =
                                InboxMessage(
                                    eventId = row[InboxMessageTable.eventId],
                                    reference = row[InboxMessageTable.reference],
                                    content = row[InboxMessageTable.content],
                                ),
                            claimToken = claimToken,
                        )
                    }
            if (claimed.isNotEmpty()) {
                InboxMessageTable.update({ InboxMessageTable.eventId inList claimed.map { it.message.eventId } }) {
                    it[state] = InboxMessageState.CLAIMED.name
                    it[InboxMessageTable.claimToken] = claimToken.value
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
        claim: InboxClaim,
        maxAttempts: Int,
    ): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            InboxMessageTable.update({
                (InboxMessageTable.eventId eq claim.eventId) and
                    (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                    (InboxMessageTable.claimToken eq claim.token.value) and
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
            it[claimToken] = null
            it[nextAttemptTime] = null
            it[processedAt] = now
            it[waitReason] = null
            it[errorMessage] = "Poison row failed after reaching $maxAttempts attempts"
        }
        logger.event(
            InboxRepositoryLogEvents.poisonRowsFailed,
            PoisonInboxContext(
                poisonCount = poisonIds.size,
                maxAttempts = maxAttempts,
            ),
        )
    }

    override fun markProcessedInTransaction(claim: InboxClaim): Boolean =
        terminate(claim, state = InboxMessageState.PROCESSED, dropReason = null, errorMessage = null)

    override fun markDroppedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean = terminate(claim, state = InboxMessageState.DROPPED, dropReason = reason, errorMessage = null)

    override fun markFailedInTransaction(
        claim: InboxClaim,
        reason: String,
    ): Boolean = terminate(claim, state = InboxMessageState.FAILED, dropReason = null, errorMessage = reason)

    override fun markOutsideSendingWindowInTransaction(
        claim: InboxClaim,
        reason: String,
        nextRetry: Instant,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq claim.eventId) and
                (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                (InboxMessageTable.claimToken eq claim.token.value)
        }) {
            it[InboxMessageTable.state] = InboxMessageState.WAIT.name
            it[InboxMessageTable.claimToken] = null
            it[InboxMessageTable.waitReason] = reason
            it[InboxMessageTable.nextAttemptTime] = nextRetry
            // Reaching a hold decision is a successful evaluation, not a failure. The attempt spent
            // by beginAttempt is handed back, so repeated sending-window holds can never push a
            // legitimately waiting message into the poison gate.
            it[attempt] = 0
        } > 0

    override fun lockMicrofrontendCommandInTransaction(
        eventId: UUID,
        key: MicrofrontendKey,
    ): MicrofrontendCommand? {
        val connection = TransactionManager.current().connection.connection as Connection
        connection.prepareStatement(LOCK_KEY).use { statement ->
            statement.setLong(1, microfrontendLockKey(key))
            statement.executeQuery().use { result ->
                check(result.next()) { "PostgreSQL advisory lock query returned no result" }
            }
        }
        val receivedAt =
            InboxMessageTable
                .select(InboxMessageTable.receivedAt)
                .where { InboxMessageTable.eventId eq eventId }
                .singleOrNull()
                ?.get(InboxMessageTable.receivedAt)
                ?: return null
        val locked =
            connection.prepareStatement(LOCK_COMMAND_AND_EARLIER_SCHEDULED_DISABLES).use { statement ->
                statement.setObject(1, eventId)
                statement.setKey(2, key)
                statement.setObject(4, eventId)
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getObject(1, UUID::class.java)) }
                }
            }
        val laterCommandDecided =
            connection.prepareStatement(LATER_COMMAND_DECIDED).use { statement ->
                statement.setKey(1, key)
                statement.setObject(3, eventId)
                statement.executeQuery().use { result ->
                    check(result.next()) { "EXISTS query returned no result" }
                    result.getBoolean(1)
                }
            }
        return MicrofrontendCommand(
            receivedAt = receivedAt,
            earlierScheduledDisables = locked - eventId,
            laterCommandDecided = laterCommandDecided,
        )
    }

    override fun cancelPendingInTransaction(eventIds: List<UUID>): Int {
        if (eventIds.isEmpty()) {
            return 0
        }
        return InboxMessageTable.update({
            (InboxMessageTable.eventId inList eventIds) and (InboxMessageTable.state inList PENDING_STATES)
        }) {
            it[state] = InboxMessageState.PROCESSED.name
            it[claimToken] = null
            it[nextAttemptTime] = null
            it[waitReason] = null
            it[processedAt] = Clock.System.now()
        }
    }

    override fun saveDerivedInTransaction(
        sourceEventId: UUID,
        message: InboxMessage,
        waitReason: String,
        notBefore: Instant,
    ): Boolean {
        val sourceReceivedAt =
            InboxMessageTable
                .select(InboxMessageTable.receivedAt)
                .where { InboxMessageTable.eventId eq sourceEventId }
                .single()[InboxMessageTable.receivedAt]
        return InboxMessageTable
            .insertIgnore {
                it[eventId] = message.eventId
                it[content] = message.content
                it[reference] = message.reference
                it[state] = InboxMessageState.WAIT.name
                it[nextAttemptTime] = notBefore
                it[InboxMessageTable.waitReason] = waitReason
                it[receivedAt] = sourceReceivedAt
                it[InboxMessageTable.sourceEventId] = sourceEventId
            }.insertedCount > 0
    }

    private fun PreparedStatement.setKey(
        index: Int,
        key: MicrofrontendKey,
    ) {
        setString(index, key.personIdentifier.value)
        setString(index + 1, key.microfrontendId)
    }

    private fun terminate(
        claim: InboxClaim,
        state: InboxMessageState,
        dropReason: String?,
        errorMessage: String?,
    ): Boolean =
        InboxMessageTable.update({
            (InboxMessageTable.eventId eq claim.eventId) and
                (InboxMessageTable.state eq InboxMessageState.CLAIMED.name) and
                (InboxMessageTable.claimToken eq claim.token.value)
        }) {
            it[InboxMessageTable.state] = state.name
            it[InboxMessageTable.claimToken] = null
            it[InboxMessageTable.dropReason] = dropReason
            it[InboxMessageTable.errorMessage] = errorMessage
            it[InboxMessageTable.waitReason] = null
            it[processedAt] = Clock.System.now()
        } > 0

    companion object {
        private val PENDING_STATES =
            listOf(InboxMessageState.RECEIVED.name, InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)

        private const val LOCK_KEY = "SELECT pg_advisory_xact_lock(?)"

        // A scheduled disable takes its source command's place in ingest order.
        private const val COMMAND_POSITION =
            "(SELECT c.received_at, COALESCE(c.source_event_id, c.event_id) FROM inbox_message c WHERE c.event_id = ?)"

        // Matches the expressions of inbox_message_microfrontend_key_idx, so the index serves the lookup.
        private const val KEY_MATCH = "(m.content ->> 'personIdentifier') = ? AND (m.content ->> 'microfrontendId') = ?"

        private val LOCK_COMMAND_AND_EARLIER_SCHEDULED_DISABLES =
            """
            SELECT m.event_id
            FROM inbox_message m
            WHERE m.event_id = ?
               OR ($KEY_MATCH
                   AND m.source_event_id IS NOT NULL
                   AND m.state IN ('${InboxMessageState.WAIT.name}', '${InboxMessageState.CLAIMED.name}')
                   AND (m.received_at, m.source_event_id) < $COMMAND_POSITION)
            ORDER BY m.received_at, m.event_id
            FOR UPDATE OF m
            """.trimIndent()

        private val LATER_COMMAND_DECIDED =
            """
            SELECT EXISTS (
                SELECT 1
                FROM inbox_message m
                WHERE $KEY_MATCH
                  AND m.source_event_id IS NULL
                  AND m.state = '${InboxMessageState.PROCESSED.name}'
                  AND (m.received_at, m.event_id) > $COMMAND_POSITION
            )
            """.trimIndent()

        /**
         * Hashes the key into PostgreSQL's single-bigint advisory lock space, which is separate from
         * the two-integer space retention uses. A collision only serialises two unrelated keys.
         */
        internal fun microfrontendLockKey(key: MicrofrontendKey): Long =
            ByteBuffer
                .wrap(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest("budstikka:microfrontend:${key.personIdentifier.value}:${key.microfrontendId}".toByteArray()),
                ).long
    }
}
