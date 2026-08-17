package no.nav.budstikka.infrastructure.database.retention

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionCounts
import no.nav.budstikka.application.retention.RetentionResult
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.Database
import java.sql.DriverManager
import kotlin.time.Duration.Companion.days

class LockingIntegrationTest :
    FunSpec({
        val support = RepositoryTestSupport()

        beforeSpec { support.migrate() }
        afterTest { support.reset() }
        afterSpec { support.close() }

        test("skips safely while another database session holds the advisory lock") {
            support.inbox(support.clock.now() - 101.days)

            support.withExclusiveCleanup {
                DriverManager.getConnection(support.fixture.jdbcUrl, support.fixture.username, support.fixture.password).use { connection ->
                    connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)").use { statement ->
                        statement.setInt(1, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_NAMESPACE)
                        statement.setInt(2, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_KEY)
                        statement.executeQuery().use { resultSet ->
                            resultSet.next() shouldBe true
                            resultSet.getBoolean(1) shouldBe true
                        }
                    }
                    try {
                        support.repository.run(batchSize = 100) shouldBe RetentionResult.SkippedDueToLockContention
                        support.rowCounts() shouldBe RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0)
                    } finally {
                        connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)").use { statement ->
                            statement.setInt(1, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_NAMESPACE)
                            statement.setInt(2, RetentionRepositoryImpl.RETENTION_CLEANUP_LOCK_KEY)
                            statement.executeQuery().close()
                        }
                    }
                }

                support.repository.run(batchSize = 100) shouldBe
                    RetentionResult.Completed(
                        RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                    )
            }
        }

        test("releases the cleanup lock after a deletion transaction fails") {
            support.inbox(support.clock.now() - 101.days)

            support.withExclusiveCleanup {
                support.dataSource().use { failingDataSource ->
                    support.dataSource().use { followingDataSource ->
                        val failingCleanup = RetentionRepositoryImpl(Database.connect(failingDataSource), support.policy, support.clock)
                        val followingCleanup = RetentionRepositoryImpl(Database.connect(followingDataSource), support.policy, support.clock)
                        support.installInboxDeletionFailure()
                        try {
                            shouldThrow<ExposedSQLException> {
                                failingCleanup.run(batchSize = 100)
                            }
                        } finally {
                            support.removeInboxDeletionFailure()
                        }

                        followingCleanup.run(batchSize = 100) shouldBe
                            RetentionResult.Completed(
                                RetentionCounts(inboxMessages = 1, deadLetterMessages = 0, deliveries = 0),
                            )
                    }
                }
            }
        }
    })
