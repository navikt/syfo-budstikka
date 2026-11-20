package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.application.port.OrderedCommand
import no.nav.budstikka.domain.decision.OrderingKey
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** PostgreSQL locking and ingest order for the inbox rows that change one [OrderingKey]. */
internal class PostgresInboxOrdering private constructor(
    val lockId: Long,
    private val matchingRows: Op<Boolean>,
) {
    fun lockCommandInTransaction(eventId: UUID): OrderedCommand? {
        lockKeyInTransaction()
        val position = ingestPosition(eventId) ?: return null
        val locked = lockCommandAndEarlierScheduled(eventId, position)
        return OrderedCommand(
            receivedAt = position.receivedAt,
            earlierScheduled = locked - eventId,
            laterCommandDecided = laterCommandDecided(position),
        )
    }

    private fun lockKeyInTransaction() {
        val connection = TransactionManager.current().connection.connection as Connection
        connection.prepareStatement(SET_LOCAL_LOCK_TIMEOUT).use { statement ->
            statement.setString(1, "${KEY_LOCK_TIMEOUT.inWholeMilliseconds}ms")
            statement.executeQuery().close()
        }
        connection.prepareStatement(LOCK_KEY).use { statement ->
            statement.setLong(1, lockId)
            statement.executeQuery().use { result ->
                check(result.next()) { "PostgreSQL advisory lock query returned no result" }
            }
        }
    }

    /** A scheduled row takes the ingest position of the command it came from. */
    private data class IngestPosition(
        val receivedAt: Instant,
        val commandEventId: UUID,
    )

    private fun ingestPosition(eventId: UUID): IngestPosition? =
        InboxMessageTable
            .select(InboxMessageTable.receivedAt, InboxMessageTable.sourceEventId)
            .where { InboxMessageTable.eventId eq eventId }
            .singleOrNull()
            ?.let { IngestPosition(it[InboxMessageTable.receivedAt], it[InboxMessageTable.sourceEventId] ?: eventId) }

    private fun lockCommandAndEarlierScheduled(
        eventId: UUID,
        position: IngestPosition,
    ): List<UUID> =
        InboxMessageTable
            .select(InboxMessageTable.eventId)
            .where { (InboxMessageTable.eventId eq eventId) or (matchingRows and scheduledBefore(position)) }
            .orderBy(
                InboxMessageTable.receivedAt to SortOrder.ASC,
                InboxMessageTable.eventId to SortOrder.ASC,
            ).forUpdate()
            .map { it[InboxMessageTable.eventId] }

    private fun laterCommandDecided(position: IngestPosition): Boolean =
        !InboxMessageTable
            .select(InboxMessageTable.eventId)
            .where { matchingRows and commandDecidedAfter(position) }
            .empty()

    private fun scheduledBefore(position: IngestPosition): Op<Boolean> =
        InboxMessageTable.sourceEventId.isNotNull() and
            (InboxMessageTable.state inList listOf(InboxMessageState.WAIT.name, InboxMessageState.CLAIMED.name)) and
            ingestedBefore(InboxMessageTable.sourceEventId, position)

    private fun commandDecidedAfter(position: IngestPosition): Op<Boolean> =
        InboxMessageTable.sourceEventId.isNull() and
            (InboxMessageTable.state eq InboxMessageState.PROCESSED.name) and
            ingestedAfter(InboxMessageTable.eventId, position)

    private fun <S : UUID?> ingestedBefore(
        commandEventId: ExpressionWithColumnType<S>,
        position: IngestPosition,
    ): Op<Boolean> =
        (InboxMessageTable.receivedAt less position.receivedAt) or
            ((InboxMessageTable.receivedAt eq position.receivedAt) and (commandEventId less position.commandEventId))

    private fun <S : UUID?> ingestedAfter(
        commandEventId: ExpressionWithColumnType<S>,
        position: IngestPosition,
    ): Op<Boolean> =
        (InboxMessageTable.receivedAt greater position.receivedAt) or
            ((InboxMessageTable.receivedAt eq position.receivedAt) and (commandEventId greater position.commandEventId))

    companion object {
        private val KEY_LOCK_TIMEOUT = 5.seconds
        private const val SET_LOCAL_LOCK_TIMEOUT = "SELECT set_config('lock_timeout', ?, true)"
        private const val LOCK_KEY = "SELECT pg_advisory_xact_lock(?)"

        fun forKey(key: OrderingKey): PostgresInboxOrdering =
            when (key) {
                is OrderingKey.Microfrontend ->
                    PostgresInboxOrdering(
                        lockId = hashKey("budstikka:microfrontend:${key.personIdentifier.value}:${key.microfrontendId}"),
                        matchingRows = InboxMessageTable.microfrontendIs(key.personIdentifier.value, key.microfrontendId),
                    )
            }

        /**
         * Uses PostgreSQL's single-bigint lock space, separate from retention's two-integer space.
         * A collision only serialises unrelated keys. Keep the hash stable across rolling deploys.
         */
        private fun hashKey(name: String): Long = ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(name.toByteArray())).long
    }
}
