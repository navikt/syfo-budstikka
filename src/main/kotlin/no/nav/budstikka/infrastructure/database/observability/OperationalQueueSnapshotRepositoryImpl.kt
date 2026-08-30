package no.nav.budstikka.infrastructure.database.observability

import no.nav.budstikka.application.observability.DeliveryQueueKey
import no.nav.budstikka.application.observability.DeliveryQueueState
import no.nav.budstikka.application.observability.InboxQueueState
import no.nav.budstikka.application.observability.OperationalQueueSnapshot
import no.nav.budstikka.application.observability.OperationalQueueSnapshotRepository
import no.nav.budstikka.application.observability.QueueStats
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import kotlin.time.Instant

class OperationalQueueSnapshotRepositoryImpl(
    private val database: Database,
) : OperationalQueueSnapshotRepository {
    override suspend fun snapshot(observedAt: Instant): OperationalQueueSnapshot =
        database.transact {
            val inbox = mutableMapOf<InboxQueueState, QueueStats>()
            val deliveries = mutableMapOf<DeliveryQueueKey, QueueStats>()
            val connection = TransactionManager.current().connection.connection as Connection

            connection.prepareStatement(SNAPSHOT_SQL).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.setTimestamp(1, observedAt.toSqlTimestamp())
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        val stats = rows.readStats()
                        when (rows.getString("queue_type")) {
                            "INBOX" -> {
                                val state = InboxQueueState.valueOf(rows.getString("queue_state"))
                                check(inbox.put(state, stats) == null) { "Duplicate inbox queue state: $state" }
                            }

                            "DELIVERY" -> {
                                val key =
                                    DeliveryQueueKey(
                                        channel = Channel.valueOf(rows.getString("channel")),
                                        state = DeliveryQueueState.valueOf(rows.getString("queue_state")),
                                    )
                                check(deliveries.put(key, stats) == null) { "Duplicate delivery queue key: $key" }
                            }

                            else -> error("Unknown operational queue type")
                        }
                    }
                }
            }

            OperationalQueueSnapshot(
                observedAt = observedAt,
                inbox = inbox,
                deliveries = deliveries,
            )
        }

    private fun ResultSet.readStats(): QueueStats {
        val size = getLong("queue_size")
        val oldest = getTimestamp("oldest_at")?.toInstant()
        return QueueStats(
            size = size,
            oldestAt = oldest?.let { Instant.fromEpochSeconds(it.epochSecond, it.nano.toLong()) },
        )
    }

    private fun Instant.toSqlTimestamp(): Timestamp =
        Timestamp.from(java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong()))

    companion object {
        private const val QUERY_TIMEOUT_SECONDS = 5
        private const val SNAPSHOT_SQL =
            """
            WITH observation AS (
                SELECT CAST(? AS TIMESTAMPTZ) AS observed_at
            ),
            inbox AS (
                SELECT
                    CASE
                        WHEN state = 'RECEIVED'
                          OR (state IN ('CLAIMED', 'WAIT')
                              AND COALESCE(next_attempt_time, '-infinity'::TIMESTAMPTZ) <= observation.observed_at)
                            THEN 'DUE'
                        WHEN state = 'CLAIMED' THEN 'IN_FLIGHT'
                        WHEN state = 'WAIT' THEN 'WAITING'
                    END AS queue_state,
                    COUNT(*) AS queue_size,
                    MIN(
                        CASE
                            WHEN state = 'RECEIVED' THEN received_at
                            WHEN state IN ('CLAIMED', 'WAIT')
                              AND COALESCE(next_attempt_time, '-infinity'::TIMESTAMPTZ) <= observation.observed_at
                                THEN COALESCE(next_attempt_time, received_at)
                            ELSE received_at
                        END
                    ) AS oldest_at
                FROM inbox_message
                CROSS JOIN observation
                WHERE state IN ('RECEIVED', 'CLAIMED', 'WAIT')
                GROUP BY 1
            ),
            deliveries AS (
                SELECT
                    channel,
                    CASE
                        WHEN state = 'READY'
                          OR (state = 'CLAIMED'
                              AND COALESCE(next_attempt_time, '-infinity'::TIMESTAMPTZ) <= observation.observed_at)
                            THEN 'DUE'
                        WHEN state = 'CLAIMED' THEN 'IN_FLIGHT'
                    END AS queue_state,
                    COUNT(*) AS queue_size,
                    MIN(
                        CASE
                            WHEN state = 'READY' THEN created_at
                            WHEN state = 'CLAIMED'
                              AND COALESCE(next_attempt_time, '-infinity'::TIMESTAMPTZ) <= observation.observed_at
                                THEN COALESCE(next_attempt_time, created_at)
                            ELSE created_at
                        END
                    ) AS oldest_at
                FROM delivery
                CROSS JOIN observation
                WHERE state IN ('READY', 'CLAIMED')
                GROUP BY channel, 2
            )
            SELECT 'INBOX' AS queue_type, NULL::TEXT AS channel, queue_state, queue_size, oldest_at
            FROM inbox
            UNION ALL
            SELECT 'DELIVERY' AS queue_type, channel, queue_state, queue_size, oldest_at
            FROM deliveries
            """
    }
}
