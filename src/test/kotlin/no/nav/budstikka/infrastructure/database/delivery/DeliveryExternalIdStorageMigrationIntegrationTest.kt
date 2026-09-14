package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class DeliveryExternalIdStorageMigrationIntegrationTest :
    FunSpec({
        test("V10 backfills source CREATE rows and fails closed for V9's ambiguous fallback") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("8")
                val recoverable = LegacyCreate()
                val ambiguous = LegacyCreate()
                fixture.insertLegacyCreate(recoverable)
                fixture.insertLegacyCreate(ambiguous)
                fixture.deleteInbox(ambiguous.eventId)
                fixture.migrateTo("9")
                fixture.setExternalId(recoverable.id, null)

                fixture.createExternalId(recoverable.id) shouldBe null
                fixture.createExternalId(ambiguous.id) shouldBe ambiguous.id.toString()

                fixture.migrateTo("10")

                fixture.createExternalId(recoverable.id) shouldBe recoverable.eventId.toString()
                fixture.createExternalId(ambiguous.id) shouldBe null
            }
        }

        test("V10 trigger fills compatible legacy CREATE inserts and leaves other rows untouched") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("10")
                val compatible = LegacyCreate()
                val otherChannel = LegacyCreate()
                val otherOperation = LegacyCreate()

                fixture.insertLegacyDelivery(compatible, "CREATE", "ARBEIDSGIVERVARSEL")
                fixture.insertLegacyDelivery(otherChannel, "CREATE", "BRUKERVARSEL")
                fixture.insertLegacyDelivery(otherOperation, "INACTIVATE", "ARBEIDSGIVERVARSEL")

                fixture.createExternalId(compatible.id) shouldBe compatible.eventId.toString()
                fixture.createExternalId(otherChannel.id) shouldBe null
                fixture.createExternalId(otherOperation.id) shouldBe null
            }
        }

        test("a legacy writer cannot insert between V10 repair and trigger installation") {
            PostgresTestFixture().use { fixture ->
                val repaired = LegacyCreate()
                val concurrent = LegacyCreate()
                fixture.migrateTo("9")
                fixture.insertLegacyCreate(repaired)

                DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { blocker ->
                    val migrationExecutor = Executors.newSingleThreadExecutor()
                    val writerExecutor = Executors.newSingleThreadExecutor()
                    try {
                        blocker.autoCommit = false
                        val blockerPid =
                            blocker.prepareStatement("SELECT pg_backend_pid()").use { statement ->
                                statement.executeQuery().use { rows ->
                                    rows.next() shouldBe true
                                    rows.getInt(1)
                                }
                            }
                        blocker.prepareStatement("SELECT id FROM delivery WHERE id = ? FOR UPDATE").use { statement ->
                            statement.setObject(1, repaired.id)
                            statement.executeQuery().use { rows -> rows.next() shouldBe true }
                        }

                        val migrationStarted = CountDownLatch(1)
                        val migration =
                            migrationExecutor.submit {
                                migrationStarted.countDown()
                                fixture.migrateTo("10")
                            }
                        migrationStarted.awaitOrFail("V10 migration did not start")
                        fixture.awaitWaitingDatabaseLock(blockerPid)

                        val writer = writerExecutor.submit { fixture.insertLegacyCreate(concurrent) }
                        fixture.awaitWriterAtMigrationBoundary(writer)
                        writer.isDone shouldBe false

                        blocker.commit()
                        migration.get(10, TimeUnit.SECONDS)
                        writer.get(10, TimeUnit.SECONDS)

                        fixture.createExternalId(repaired.id) shouldBe repaired.eventId.toString()
                        fixture.createExternalId(concurrent.id) shouldBe concurrent.eventId.toString()
                    } finally {
                        runCatching { blocker.rollback() }
                        migrationExecutor.shutdownNow()
                        writerExecutor.shutdownNow()
                        migrationExecutor.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
                        writerExecutor.awaitTermination(10, TimeUnit.SECONDS) shouldBe true
                    }
                }
            }
        }

        test("V11 adds the source CREATE dependency column and index") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("10")
                fixture.deliveryColumnExists("source_create_delivery_id") shouldBe false

                fixture.migrate()

                fixture.deliveryColumnExists("source_create_delivery_id") shouldBe true
                fixture.deliverySelfReferenceExists("source_create_delivery_id") shouldBe true
                fixture.deliveryIndexExists("delivery_source_create_delivery_id_idx") shouldBe true
            }
        }
    })

private fun PostgresTestFixture.awaitWaitingDatabaseLock(blockerPid: Int) {
    val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
    while (System.nanoTime() < deadline) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks AS waiting
                        JOIN pg_locks AS held ON held.transactionid = waiting.transactionid
                        WHERE NOT waiting.granted
                          AND waiting.locktype = 'transactionid'
                          AND held.granted
                          AND held.pid = ?
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.setInt(1, blockerPid)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        if (rows.getBoolean(1)) return
                    }
                }
        }
        Thread.sleep(10)
    }
    error("V10 repair did not wait for the target row lock")
}

private fun PostgresTestFixture.awaitWriterAtMigrationBoundary(writer: Future<*>) {
    val deadline = System.nanoTime() + 5.seconds.inWholeNanoseconds
    while (System.nanoTime() < deadline && !writer.isDone) {
        DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM pg_locks
                        WHERE NOT granted
                          AND locktype = 'relation'
                          AND relation = 'delivery'::regclass
                          AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                    )
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        if (rows.getBoolean(1)) return
                    }
                }
        }
        Thread.sleep(10)
    }
    error("Legacy writer did not wait for this fixture's delivery relation lock")
}

private fun CountDownLatch.awaitOrFail(message: String) {
    check(await(10, TimeUnit.SECONDS)) { message }
}

private fun PostgresTestFixture.deliveryColumnExists(column: String): Boolean =
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'delivery'
                      AND column_name = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, column)
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }

private fun PostgresTestFixture.deliveryIndexExists(index: String): Boolean =
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT to_regclass(?::text) IS NOT NULL",
            ).use { statement ->
                statement.setString(1, "$schema.$index")
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }

private fun PostgresTestFixture.deliverySelfReferenceExists(column: String): Boolean =
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint
                    JOIN pg_attribute
                      ON pg_attribute.attrelid = conrelid
                     AND pg_attribute.attnum = ANY (conkey)
                    WHERE contype = 'f'
                      AND conrelid = ?::regclass
                      AND confrelid = ?::regclass
                      AND pg_attribute.attname = ?
                )
                """.trimIndent(),
            ).use { statement ->
                val delivery = "$schema.delivery"
                statement.setString(1, delivery)
                statement.setString(2, delivery)
                statement.setString(3, column)
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }
