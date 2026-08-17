package no.nav.budstikka.infrastructure.database.retention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionPolicy
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Instant

internal class RepositoryTestSupport : AutoCloseable {
    val fixture = PostgresTestFixture()
    val clock = MutableClock(Instant.parse("2026-08-14T08:00:00Z"))
    val policy = RetentionPolicy()
    val repository = RetentionRepositoryImpl(fixture.database, policy, clock)

    fun migrate() = fixture.migrate()

    fun reset() = fixture.reset()

    override fun close() = fixture.close()

    suspend fun run(batchSize: Int): RetentionResult = withExclusiveCleanup { repository.run(batchSize) }

    suspend fun <T> withExclusiveCleanup(block: suspend () -> T): T = retentionCleanupMutex.withLock { block() }

    suspend fun inbox(receivedAt: Instant): UUID {
        val eventId = UUID.randomUUID()
        val message = inboxMessage(eventId)
        fixture.database.transact {
            InboxMessageTable.insert {
                it[InboxMessageTable.eventId] = eventId
                it[content] = message.content
                it[reference] = message.reference
                it[InboxMessageTable.receivedAt] = receivedAt
            }
        }
        return eventId
    }

    suspend fun deadLetter(
        receivedAt: Instant,
        offset: Long,
    ): UUID {
        val id = UUID.randomUUID()
        fixture.database.transact {
            DeadLetterMessageTable.insert {
                it[DeadLetterMessageTable.id] = id
                it[payload] = """{"reference":"retention-test"}"""
                it[topic] = "retention-test"
                it[partition] = 0
                it[kafkaOffset] = offset
                it[failureReason] = "UNPARSEABLE_PAYLOAD"
                it[DeadLetterMessageTable.receivedAt] = receivedAt
            }
        }
        return id
    }

    suspend fun delivery(
        createdAt: Instant,
        state: DeliveryState,
        inboxEventId: UUID? = null,
    ): UUID {
        val id = UUID.randomUUID()
        fixture.database.transact {
            DeliveryTable.insert {
                it[DeliveryTable.id] = id
                it[DeliveryTable.inboxEventId] = inboxEventId
                it[reference] = "retention-test"
                it[operation] = "CREATE"
                it[channel] = "MICROFRONTEND"
                it[recipientType] = "PERSON"
                it[recipientId] = "recipient"
                it[payload] = inboxMessage(UUID.randomUUID()).content
                it[DeliveryTable.state] = state.name
                it[DeliveryTable.createdAt] = createdAt
            }
        }
        return id
    }

    suspend fun rowCounts(): RetentionCounts =
        fixture.database.transact {
            RetentionCounts(
                inboxMessages = InboxMessageTable.selectAll().count().toInt(),
                deadLetterMessages = DeadLetterMessageTable.selectAll().count().toInt(),
                deliveries = DeliveryTable.selectAll().count().toInt(),
            )
        }

    fun dataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = fixture.jdbcUrl
                username = fixture.username
                password = fixture.password
                maximumPoolSize = 1
                minimumIdle = 1
            },
        )

    fun installInboxDeletionFailure() =
        executeSql(
            """
            CREATE FUNCTION fail_retention_cleanup()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                RAISE EXCEPTION 'forced retention cleanup failure';
            END;
            $$;
            """.trimIndent(),
            """
            CREATE TRIGGER retention_cleanup_failure
            BEFORE DELETE ON inbox_message
            FOR EACH ROW
            EXECUTE FUNCTION fail_retention_cleanup();
            """.trimIndent(),
        )

    fun removeInboxDeletionFailure() =
        executeSql(
            "DROP TRIGGER retention_cleanup_failure ON inbox_message",
            "DROP FUNCTION fail_retention_cleanup()",
        )

    private fun executeSql(vararg statements: String) {
        DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
            connection.createStatement().use { statement ->
                statements.forEach(statement::executeUpdate)
            }
        }
    }

    companion object {
        private val retentionCleanupMutex = Mutex()
    }
}
