package no.nav.budstikka.infrastructure.database.delivery

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArguments.kv
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.application.port.ClaimedDelivery
import no.nav.budstikka.application.port.DeliveryAttempt
import no.nav.budstikka.application.port.DeliveryClaimResult
import no.nav.budstikka.application.port.DeliveryRepository
import no.nav.budstikka.application.port.SourceSendGuardResult
import no.nav.budstikka.application.port.StoredCreateDelivery
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

class DeliveryRepositoryImpl(
    private val database: Database,
    private val dataSource: HikariDataSource,
) : DeliveryRepository {
    private val logger = LoggerFactory.getLogger(DeliveryRepositoryImpl::class.java)

    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        if (draft.isEmpty()) {
            return
        }
        DeliveryTable.batchInsert(draft) { draftEntry ->
            this[DeliveryTable.inboxEventId] = inboxEventId
            this[DeliveryTable.reference] = draftEntry.reference
            this[DeliveryTable.operation] = draftEntry.operation.name
            this[DeliveryTable.channel] = draftEntry.channel.name
            val (type, id) = draftEntry.recipient.toColumns()
            this[DeliveryTable.recipientType] = type
            this[DeliveryTable.recipientId] = id
            this[DeliveryTable.payload] = draftEntry.content
            this[DeliveryTable.createExternalId] =
                if (draftEntry.operation == Operation.CREATE && draftEntry.channel == Channel.ARBEIDSGIVERVARSEL) {
                    inboxEventId.toString()
                } else {
                    draftEntry.createExternalId
                }
            this[DeliveryTable.sourceCreateDeliveryId] = draftEntry.sourceCreateDeliveryId
            this[DeliveryTable.createdAt] = Clock.System.now()
        }
    }

    override fun findCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<StoredCreateDelivery> {
        val (recipientType, recipientId) = match.recipient.toColumns()
        return DeliveryTable
            .select(
                DeliveryTable.id,
                DeliveryTable.createExternalId,
                DeliveryTable.reference,
                DeliveryTable.channel,
                DeliveryTable.recipientType,
                DeliveryTable.recipientId,
                DeliveryTable.payload,
            ).where {
                (DeliveryTable.reference eq match.reference) and
                    (DeliveryTable.operation eq Operation.CREATE.name) and
                    (DeliveryTable.channel eq match.channel.name) and
                    (DeliveryTable.recipientType eq recipientType) and
                    (DeliveryTable.recipientId eq recipientId)
            }.orderBy(
                DeliveryTable.createdAt to SortOrder.ASC,
                DeliveryTable.id to SortOrder.ASC,
            ).map { row ->
                StoredCreateDelivery(
                    id = row[DeliveryTable.id],
                    createExternalId = row[DeliveryTable.createExternalId],
                    reference = row[DeliveryTable.reference],
                    channel = Channel.valueOf(row[DeliveryTable.channel]),
                    recipient = recipientFromColumns(row[DeliveryTable.recipientType], row[DeliveryTable.recipientId]),
                    payload = row[DeliveryTable.payload],
                )
            }
    }

    override suspend fun claim(
        limit: Int,
        lease: Duration,
        maxAttempts: Int,
        channels: Set<Channel>,
    ): DeliveryClaimResult {
        require(limit > 0) { "limit must be greater than 0" }
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        require(channels.isNotEmpty()) { "channels must not be empty" }
        return database.transact {
            val now = Clock.System.now()
            val channelNames = channels.map(Channel::name)
            val failedSourceDependencies = failDependenciesWithFailedSources(channelNames, limit)
            failPoisonRows(now, maxAttempts, channelNames)
            val claimedIds = selectClaimableIds(maxAttempts, channelNames, limit)
            val claimed =
                DeliveryTable
                    .select(
                        DeliveryTable.id,
                        DeliveryTable.inboxEventId,
                        DeliveryTable.reference,
                        DeliveryTable.operation,
                        DeliveryTable.channel,
                        DeliveryTable.payload,
                        DeliveryTable.createExternalId,
                        DeliveryTable.sourceCreateDeliveryId,
                    ).where { DeliveryTable.id inList claimedIds }
                    .orderBy(
                        DeliveryTable.createdAt to SortOrder.ASC,
                        DeliveryTable.id to SortOrder.ASC,
                    ).map { row ->
                        ClaimedDelivery(
                            id = row[DeliveryTable.id],
                            inboxEventId = row[DeliveryTable.inboxEventId],
                            reference = row[DeliveryTable.reference],
                            channel = Channel.valueOf(row[DeliveryTable.channel]),
                            payload = row[DeliveryTable.payload],
                            operation = Operation.valueOf(row[DeliveryTable.operation]),
                            createExternalId = row[DeliveryTable.createExternalId],
                            sourceCreateDeliveryId = row[DeliveryTable.sourceCreateDeliveryId],
                        )
                    }
            if (claimed.isNotEmpty()) {
                DeliveryTable.update({ DeliveryTable.id inList claimed.map { it.id } }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[nextAttemptTime] = now + lease
                }
            }

            DeliveryClaimResult(
                deliveries = claimed,
                failedSourceDependencies = failedSourceDependencies,
            )
        }
    }

    /**
     * Finds and locks candidates in the claim transaction. The correlated source lookup keeps a
     * derived INACTIVATE out of the queue until its exact CREATE is durably SENT. Rows without a
     * source relationship retain the existing claim behavior.
     */
    private fun selectClaimableIds(
        maxAttempts: Int,
        channelNames: List<String>,
        limit: Int,
    ): List<UUID> {
        val channelParameters = channelNames.joinToString(", ") { "?" }
        val sql =
            """
            SELECT delivery.id
            FROM delivery
            WHERE (
                delivery.state = 'READY'
                OR (
                    delivery.state = 'CLAIMED'
                    AND delivery.next_attempt_time <= CURRENT_TIMESTAMP
                    AND delivery.attempt < ?
                )
            )
            AND delivery.channel IN ($channelParameters)
            AND (
                delivery.operation <> 'INACTIVATE'
                OR delivery.source_create_delivery_id IS NULL
                OR EXISTS (
                    SELECT 1
                    FROM delivery AS source
                    WHERE source.id = delivery.source_create_delivery_id
                    AND source.state = 'SENT'
                )
            )
            ORDER BY delivery.created_at ASC, delivery.id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """.trimIndent()
        val connection = TransactionManager.current().connection.connection as Connection
        return connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, maxAttempts)
            channelNames.forEachIndexed { index, channel -> statement.setString(index + 2, channel) }
            statement.setInt(channelNames.size + 2, limit)
            statement.executeQuery().use { resultSet ->
                buildList {
                    while (resultSet.next()) {
                        add(resultSet.getObject(1, UUID::class.java))
                    }
                }
            }
        }
    }

    /**
     * A source CREATE that has failed can never release its dependent INACTIVATE. Terminalize it
     * before claiming, without consuming an attempt or calling a channel handler.
     */
    private fun failDependenciesWithFailedSources(
        channelNames: List<String>,
        limit: Int,
    ): Int {
        var failedDependencies = 0
        val connection = TransactionManager.current().connection.connection as Connection
        val channelParameters = channelNames.joinToString(", ") { "?" }
        val sql = FAIL_DEPENDENCIES_WITH_FAILED_SOURCES.replace(CHANNEL_PARAMETERS, channelParameters)
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, Operation.INACTIVATE.name)
            statement.setString(2, DeliveryState.FAILED.name)
            statement.setString(3, DeliveryState.READY.name)
            statement.setString(4, DeliveryState.CLAIMED.name)
            channelNames.forEachIndexed { index, channel -> statement.setString(index + 5, channel) }
            statement.setInt(channelNames.size + 5, limit)
            statement.setString(channelNames.size + 6, DeliveryState.FAILED.name)
            statement.setString(channelNames.size + 7, FAILED_SOURCE_REASON)
            statement.executeQuery().use { resultSet ->
                while (resultSet.next()) {
                    failedDependencies++
                    logger.warn(
                        "Failed dependent delivery because its source CREATE failed {} {} {} {} {}",
                        kv(MdcKeys.DELIVERY_ID, resultSet.getObject(1, UUID::class.java).toString()),
                        kv(MdcKeys.EVENT_ID, resultSet.getObject(2, UUID::class.java)?.toString()),
                        kv(MdcKeys.REFERENCE, resultSet.getString(3)),
                        kv(MdcKeys.DELIVERY_CHANNEL, resultSet.getString(4)),
                        kv(MdcKeys.REASON, FAILED_SOURCE_REASON),
                    )
                }
            }
        }
        return failedDependencies
    }

    /**
     * Spends one delivery attempt, guarded in the same statement so concurrent replicas cannot push
     * `attempt` past [maxAttempts]. Claiming deliberately does not touch `attempt`: a row that is
     * claimed but never sent (batch abort, spent lease budget, crash) must keep its budget.
     */
    override suspend fun beginAttempt(
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return database.transact {
            DeliveryTable.update({
                (DeliveryTable.id eq deliveryId) and
                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and
                    (DeliveryTable.attempt less maxAttempts)
            }) {
                it[attempt] = attempt + 1
            } > 0
        }
    }

    /**
     * PostgreSQL session advisory locks serialize sends for one CREATE across replicas without
     * keeping an Exposed transaction or inbox row lock open during HTTP. `pg_try_advisory_lock`
     * never waits for a peer; Hikari's configured connection timeout bounds pool acquisition.
     *
     * The connection stays checked out only while the handler and its terminal delivery update run.
     * The `finally` block unlocks it on success, failure, or coroutine cancellation before the
     * connection returns to Hikari's pool. A failed unlock evicts the checked-out connection so a
     * possible session lock can never be reused.
     */
    override suspend fun withSourceSendGuard(
        delivery: ClaimedDelivery,
        block: suspend (DeliveryAttempt) -> Unit,
    ): SourceSendGuardResult {
        val sourceId =
            delivery.sourceDeliveryId() ?: run {
                block(unguardedDeliveryAttempt(delivery.id))
                return SourceSendGuardResult.DISPATCHED
            }
        var cleanupFailure: Throwable? = null
        try {
            return withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    if (!connection.tryAcquireSourceSendGuard(sourceId)) {
                        SourceSendGuardResult.CONTENDED
                    } else {
                        var blockFailed = false
                        try {
                            if (!connection.isSourceEligible(sourceId, delivery.operation)) {
                                SourceSendGuardResult.SOURCE_NOT_ELIGIBLE
                            } else {
                                block(connection.deliveryAttempt(delivery.id))
                                SourceSendGuardResult.DISPATCHED
                            }
                        } catch (failure: Throwable) {
                            blockFailed = true
                            cleanupFailure = releaseSourceSendGuard(connection, sourceId)
                            throw failure
                        } finally {
                            if (!blockFailed) {
                                releaseSourceSendGuard(connection, sourceId)?.let { throw it }
                            }
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            cleanupFailure?.let(failure::addSuppressed)
            throw failure
        }
    }

    private fun releaseSourceSendGuard(
        connection: Connection,
        sourceId: UUID,
    ): Throwable? {
        val cleanupFailure =
            runCatching {
                check(connection.releaseSourceSendGuard(sourceId)) {
                    "PostgreSQL source send advisory lock was not held by this connection"
                }
            }.exceptionOrNull() ?: return null

        // Hikari marks this checked-out connection for eviction and closes it rather than returning
        // a session that may still hold the advisory lock to the pool.
        val evicted =
            runCatching { dataSource.evictConnection(connection) }
                .onFailure { evictionFailure -> cleanupFailure.addSuppressed(evictionFailure) }
                .isSuccess
        if (evicted) {
            logger.error("Could not release source send advisory lock; evicted the checked-out connection")
        } else {
            logger.error("Could not release source send advisory lock or evict the checked-out connection")
        }
        return cleanupFailure
    }

    private fun unguardedDeliveryAttempt(deliveryId: UUID): DeliveryAttempt =
        object : DeliveryAttempt {
            override suspend fun beginAttempt(maxAttempts: Int): Boolean = this@DeliveryRepositoryImpl.beginAttempt(deliveryId, maxAttempts)

            override suspend fun markSent(): Boolean = this@DeliveryRepositoryImpl.markSent(deliveryId)

            override suspend fun markFailed(reason: String): Boolean = this@DeliveryRepositoryImpl.markFailed(deliveryId, reason)
        }

    /**
     * Expired CLAIMED rows that already spent [maxAttempts] delivery attempts become FAILED. Runs in
     * the same transaction as claim (limited to the
     * [channelNames] handled by this worker), so a deterministic failing row stops being reclaimed
     * and cannot block the queue head (`createdAt ASC`).
     *
     * Because `attempt` is spent by [beginAttempt] and not by claiming, a row that was claimed but
     * never handed to a handler keeps its budget and is not terminated here.
     *
     * Poison rows use `FOR UPDATE SKIP LOCKED` (like the claim), so concurrent replicas terminate
     * distinct rows without blocking each other.
     *
     * Each row takes the same per-source advisory key with a non-blocking, transaction-scoped lock.
     * It conflicts with normal dispatch's session lock and releases automatically when the
     * transaction completes or rolls back.
     */
    private fun failPoisonRows(
        now: Instant,
        maxAttempts: Int,
        channelNames: List<String>,
    ) {
        val poisonRows =
            DeliveryTable
                .select(
                    DeliveryTable.id,
                    DeliveryTable.inboxEventId,
                    DeliveryTable.reference,
                    DeliveryTable.operation,
                    DeliveryTable.channel,
                    DeliveryTable.attempt,
                    DeliveryTable.sourceCreateDeliveryId,
                ).where {
                    (DeliveryTable.state eq DeliveryState.CLAIMED.name) and
                        (DeliveryTable.nextAttemptTime lessEq now) and
                        (DeliveryTable.attempt greaterEq maxAttempts) and
                        (DeliveryTable.channel inList channelNames)
                }.forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { row ->
                    PoisonDeliveryRow(
                        id = row[DeliveryTable.id],
                        inboxEventId = row[DeliveryTable.inboxEventId],
                        reference = row[DeliveryTable.reference],
                        operation = row[DeliveryTable.operation],
                        channel = row[DeliveryTable.channel],
                        attempt = row[DeliveryTable.attempt],
                        sourceCreateDeliveryId = row[DeliveryTable.sourceCreateDeliveryId],
                    )
                }
        if (poisonRows.isEmpty()) {
            return
        }
        val connection = TransactionManager.current().connection.connection as Connection
        poisonRows.forEach { row ->
            val sourceId = row.sourceDeliveryId()
            if (sourceId != null && !connection.tryAcquirePoisonProcessingGuard(sourceId)) {
                return@forEach
            }
            if (!connection.failPoisonRow(row.id, maxAttempts)) {
                return@forEach
            }
            logger.warn(
                "Failed poison delivery row after reaching max attempts {} {} {} {} {} {} {}",
                kv(MdcKeys.DELIVERY_ID, row.id.toString()),
                kv(MdcKeys.EVENT_ID, row.inboxEventId?.toString()),
                kv(MdcKeys.REFERENCE, row.reference),
                kv(MdcKeys.OPERATION, row.operation),
                kv(MdcKeys.DELIVERY_CHANNEL, row.channel),
                kv(MdcKeys.DELIVERY_COUNT, row.attempt),
                kv(MdcKeys.MAX_ATTEMPTS, maxAttempts),
            )
        }
    }

    private data class PoisonDeliveryRow(
        val id: UUID,
        val inboxEventId: UUID?,
        val reference: String,
        val operation: String,
        val channel: String,
        val attempt: Int,
        val sourceCreateDeliveryId: UUID?,
    )

    private fun PoisonDeliveryRow.sourceDeliveryId(): UUID? =
        when (Operation.valueOf(operation)) {
            Operation.CREATE -> id
            Operation.INACTIVATE -> sourceCreateDeliveryId
        }

    private fun ClaimedDelivery.sourceDeliveryId(): UUID? =
        when (operation) {
            Operation.CREATE -> id
            Operation.INACTIVATE -> sourceCreateDeliveryId
        }

    private fun Connection.tryAcquireSourceSendGuard(sourceId: UUID): Boolean =
        sourceId.advisoryLockKey().queryAdvisoryLock(this, TRY_SOURCE_SEND_LOCK)

    private fun Connection.tryAcquirePoisonProcessingGuard(sourceId: UUID): Boolean =
        sourceId.advisoryLockKey().queryAdvisoryLock(this, TRY_POISON_PROCESSING_LOCK)

    private fun Connection.releaseSourceSendGuard(sourceId: UUID): Boolean =
        sourceId.advisoryLockKey().queryAdvisoryLock(this, RELEASE_SOURCE_SEND_LOCK)

    private fun Connection.isSourceEligible(
        sourceId: UUID,
        operation: Operation,
    ): Boolean =
        prepareStatement(SOURCE_ELIGIBILITY).use { statement ->
            statement.setObject(1, sourceId)
            statement.setString(
                2,
                if (operation == Operation.CREATE) DeliveryState.CLAIMED.name else DeliveryState.SENT.name,
            )
            statement.executeQuery().use { resultSet -> resultSet.next() }
        }

    private fun Connection.failPoisonRow(
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean =
        prepareStatement(FAIL_POISON_ROW).use { statement ->
            statement.setString(1, DeliveryState.FAILED.name)
            statement.setString(2, "Poison row failed after reaching $maxAttempts attempts")
            statement.setObject(3, deliveryId)
            statement.setString(4, DeliveryState.CLAIMED.name)
            statement.setInt(5, maxAttempts)
            statement.executeUpdate() > 0
        }

    private fun Connection.deliveryAttempt(deliveryId: UUID): DeliveryAttempt =
        object : DeliveryAttempt {
            override suspend fun beginAttempt(maxAttempts: Int): Boolean = this@deliveryAttempt.beginAttempt(deliveryId, maxAttempts)

            override suspend fun markSent(): Boolean = this@deliveryAttempt.markClaimedAsTerminal(deliveryId, DeliveryState.SENT, null)

            override suspend fun markFailed(reason: String): Boolean {
                require(reason.isNotBlank()) { "reason must not be blank" }
                return this@deliveryAttempt.markClaimedAsTerminal(deliveryId, DeliveryState.FAILED, reason)
            }
        }

    private fun Connection.beginAttempt(
        deliveryId: UUID,
        maxAttempts: Int,
    ): Boolean {
        require(maxAttempts > 0) { "maxAttempts must be greater than 0" }
        return prepareStatement(BEGIN_ATTEMPT).use { statement ->
            statement.setObject(1, deliveryId)
            statement.setString(2, DeliveryState.CLAIMED.name)
            statement.setInt(3, maxAttempts)
            statement.executeUpdate() > 0
        }
    }

    private fun Connection.markClaimedAsTerminal(
        deliveryId: UUID,
        state: DeliveryState,
        errorMessage: String?,
    ): Boolean =
        prepareStatement(MARK_CLAIMED_AS_TERMINAL).use { statement ->
            statement.setString(1, state.name)
            statement.setString(2, errorMessage)
            statement.setObject(3, deliveryId)
            statement.setString(4, DeliveryState.CLAIMED.name)
            statement.executeUpdate() > 0
        }

    private fun Pair<Int, Int>.queryAdvisoryLock(
        connection: Connection,
        sql: String,
    ): Boolean =
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, first)
            statement.setInt(2, second)
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "PostgreSQL advisory lock query returned no result" }
                resultSet.getBoolean(1)
            }
        }

    private fun UUID.advisoryLockKey(): Pair<Int, Int> {
        val key = mostSignificantBits xor leastSignificantBits
        return (key ushr 32).toInt() to key.toInt()
    }

    override suspend fun markSent(deliveryId: UUID): Boolean =
        markClaimedAsTerminal(deliveryId, state = DeliveryState.SENT, errorMessage = null)

    override suspend fun markFailed(
        deliveryId: UUID,
        reason: String,
    ): Boolean {
        require(reason.isNotBlank()) { "reason must not be blank" }
        return markClaimedAsTerminal(deliveryId, state = DeliveryState.FAILED, errorMessage = reason)
    }

    private suspend fun markClaimedAsTerminal(
        deliveryId: UUID,
        state: DeliveryState,
        errorMessage: String?,
    ): Boolean =
        database.transact {
            DeliveryTable.update({
                (DeliveryTable.id eq deliveryId) and (DeliveryTable.state eq DeliveryState.CLAIMED.name)
            }) {
                it[DeliveryTable.state] = state.name
                it[DeliveryTable.nextAttemptTime] = null
                it[DeliveryTable.errorMessage] = errorMessage
            } > 0
        }

    private companion object {
        const val FAILED_SOURCE_REASON = "Source CREATE delivery failed"

        val FAIL_DEPENDENCIES_WITH_FAILED_SOURCES =
            """
            WITH candidates AS (
                SELECT dependent.id
                FROM delivery AS dependent
                JOIN delivery AS source ON dependent.source_create_delivery_id = source.id
                WHERE dependent.operation = ?
                  AND source.state = ?
                  AND dependent.state IN (?, ?)
                  AND dependent.channel IN ($CHANNEL_PARAMETERS)
                ORDER BY dependent.created_at ASC, dependent.id ASC
                LIMIT ?
                FOR UPDATE OF dependent SKIP LOCKED
            )
            UPDATE delivery AS dependent
            SET state = ?,
                next_attempt_time = NULL,
                error_message = ?
            FROM candidates
            WHERE dependent.id = candidates.id
            RETURNING dependent.id, dependent.inbox_event_id, dependent.reference, dependent.channel
            """.trimIndent()

        const val CHANNEL_PARAMETERS = ":channelParameters"
        const val TRY_SOURCE_SEND_LOCK = "SELECT pg_try_advisory_lock(?, ?)"
        const val TRY_POISON_PROCESSING_LOCK = "SELECT pg_try_advisory_xact_lock(?, ?)"
        const val RELEASE_SOURCE_SEND_LOCK = "SELECT pg_advisory_unlock(?, ?)"
        const val SOURCE_ELIGIBILITY = "SELECT 1 FROM delivery WHERE id = ? AND state = ?"
        const val BEGIN_ATTEMPT =
            "UPDATE delivery SET attempt = attempt + 1 WHERE id = ? AND state = ? AND attempt < ?"
        const val MARK_CLAIMED_AS_TERMINAL =
            "UPDATE delivery SET state = ?, next_attempt_time = NULL, error_message = ? WHERE id = ? AND state = ?"
        val FAIL_POISON_ROW =
            """
            UPDATE delivery
            SET state = ?,
                next_attempt_time = NULL,
                error_message = ?
            WHERE id = ?
              AND state = ?
              AND next_attempt_time <= CURRENT_TIMESTAMP
              AND attempt >= ?
            """.trimIndent()
    }
}

private fun Recipient.toColumns(): Pair<String, String> =
    when (this) {
        is Recipient.Person -> "PERSON" to ident.value
        is Recipient.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }

private fun recipientFromColumns(
    type: String,
    id: String,
): Recipient =
    when (type) {
        "PERSON" -> Recipient.Person(PersonIdentifier(id))
        "VIRKSOMHET" -> Recipient.Virksomhet(Orgnummer(id))
        else -> error("Unknown delivery recipient type")
    }
