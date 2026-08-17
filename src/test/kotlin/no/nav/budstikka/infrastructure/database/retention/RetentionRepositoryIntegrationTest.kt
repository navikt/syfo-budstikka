package no.nav.budstikka.infrastructure.database.retention

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionResult
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryState
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RetentionRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val clock = MutableClock(Instant.parse("2026-08-14T08:00:00Z"))
        val cleanup = RetentionRepositoryImpl(fixture.database, clock)

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec {
            fixture.close()
        }

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

        suspend fun rowCounts() =
            fixture.database.transact {
                RetentionCounts(
                    inboxMessages = InboxMessageTable.selectAll().count().toInt(),
                    deadLetterMessages = DeadLetterMessageTable.selectAll().count().toInt(),
                    deliveries = DeliveryTable.selectAll().count().toInt(),
                )
            }

        fun retentionCleanupDataSource() =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = fixture.jdbcUrl
                    username = fixture.username
                    password = fixture.password
                    maximumPoolSize = 1
                    minimumIdle = 1
                },
            )

        fun installInboxDeletionFailure() {
            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
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
                    )
                    statement.executeUpdate(
                        """
                        CREATE TRIGGER retention_cleanup_failure
                        BEFORE DELETE ON inbox_message
                        FOR EACH ROW
                        EXECUTE FUNCTION fail_retention_cleanup();
                        """.trimIndent(),
                    )
                }
            }
        }

        fun removeInboxDeletionFailure() {
            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DROP TRIGGER retention_cleanup_failure ON inbox_message")
                    statement.executeUpdate("DROP FUNCTION fail_retention_cleanup()")
                }
            }
        }

        test("deletes only rows strictly older than the retention boundaries and terminal deliveries") {
            val inboxCutoff = clock.now() - 100.days
            val deliveryCutoff = clock.now() - 180.days
            val expiredInbox = inbox(inboxCutoff - 1.seconds)
            val boundaryInbox = inbox(inboxCutoff)
            val expiredDeadLetter = deadLetter(inboxCutoff - 1.seconds, offset = 1)
            val boundaryDeadLetter = deadLetter(inboxCutoff, offset = 2)
            val expiredSent = delivery(deliveryCutoff - 1.seconds, DeliveryState.SENT)
            val expiredFailed = delivery(deliveryCutoff - 1.seconds, DeliveryState.FAILED)
            val boundarySent = delivery(deliveryCutoff, DeliveryState.SENT)
            val expiredReady = delivery(deliveryCutoff - 1.seconds, DeliveryState.READY)
            val expiredClaimed = delivery(deliveryCutoff - 1.seconds, DeliveryState.CLAIMED)

            cleanup.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 2),
                )

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq expiredInbox }.count() shouldBe 0
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq boundaryInbox }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq expiredDeadLetter }.count() shouldBe 0
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq boundaryDeadLetter }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredSent }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredFailed }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq boundarySent }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredReady }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq expiredClaimed }.count() shouldBe 1
            }
        }

        test("deletes the oldest 100 candidates per table and continues on the next run") {
            val inboxCutoff = clock.now() - 100.days
            val deliveryCutoff = clock.now() - 180.days
            val inboxIds = (1..101).map { inbox(inboxCutoff - (102 - it).seconds) }
            val deadLetterIds = (1..101).map { deadLetter(inboxCutoff - (102 - it).seconds, it.toLong()) }
            val deliveryIds = (1..101).map { delivery(deliveryCutoff - (102 - it).seconds, DeliveryState.SENT) }

            cleanup.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 100, deadLetterMessages = 100, deliveries = 100),
                )
            rowCounts() shouldBe RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 1)

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq inboxIds.first() }.count() shouldBe 0
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq inboxIds.last() }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq deadLetterIds.first() }.count() shouldBe 0
                DeadLetterMessageTable.selectAll().where { DeadLetterMessageTable.id eq deadLetterIds.last() }.count() shouldBe 1
                DeliveryTable.selectAll().where { DeliveryTable.id eq deliveryIds.first() }.count() shouldBe 0
                DeliveryTable.selectAll().where { DeliveryTable.id eq deliveryIds.last() }.count() shouldBe 1
            }

            cleanup.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 1, deliveries = 1),
                )
            rowCounts() shouldBe RetentionCounts(inboxMessages = 0, deadLetterMessages = 0, deliveries = 0)
        }

        test("skips safely while another database session holds the advisory lock") {
            inbox(clock.now() - 101.days)

            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
                connection
                    .prepareStatement("SELECT pg_try_advisory_lock(?, ?)")
                    .use { statement ->
                        statement.setInt(1, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_NAMESPACE)
                        statement.setInt(2, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_KEY)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getBoolean(1) shouldBe true
                        }
                    }
                try {
                    cleanup.run(batchSize = 100) shouldBe RetentionResult.SkippedDueToLockContention
                    rowCounts() shouldBe RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0)
                } finally {
                    connection
                        .prepareStatement("SELECT pg_advisory_unlock(?, ?)")
                        .use { statement ->
                            statement.setInt(1, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_NAMESPACE)
                            statement.setInt(2, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_KEY)
                            statement.executeQuery().close()
                        }
                }
            }

            cleanup.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                )
        }

        test("releases the cleanup lock after a deletion transaction fails") {
            inbox(clock.now() - 101.days)

            retentionCleanupDataSource().use { failingDataSource ->
                retentionCleanupDataSource().use { followingDataSource ->
                    val failingCleanup = RetentionRepositoryImpl(Database.connect(failingDataSource), clock)
                    val followingCleanup = RetentionRepositoryImpl(Database.connect(followingDataSource), clock)
                    installInboxDeletionFailure()
                    try {
                        shouldThrow<ExposedSQLException> {
                            failingCleanup.run(batchSize = 100)
                        }
                    } finally {
                        removeInboxDeletionFailure()
                    }

                    followingCleanup.run(batchSize = 100) shouldBe
                        RetentionResult.Completed(
                            RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                        )
                }
            }
        }

        test("deleting an expired inbox row sets linked delivery inbox_event_id to null") {
            val inboxEventId = inbox(clock.now() - 101.days)
            val deliveryId = delivery(clock.now(), DeliveryState.READY, inboxEventId)

            cleanup.run(batchSize = 100) shouldBe
                RetentionResult.Completed(
                    RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                )

            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.id eq deliveryId }
                    .single()[DeliveryTable.inboxEventId] shouldBe null
            }
        }
    })
