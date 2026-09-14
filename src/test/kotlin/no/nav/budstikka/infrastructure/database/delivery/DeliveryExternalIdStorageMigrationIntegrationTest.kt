package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
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

                fixture.migrate()

                fixture.createExternalId(recoverable.id) shouldBe recoverable.eventId.toString()
                fixture.createExternalId(ambiguous.id) shouldBe null
            }
        }

        test("V10 trigger fills compatible legacy CREATE inserts and leaves other rows untouched") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate()
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
                        val blockerPid = blocker.backendPid()
                        blocker.prepareStatement("SELECT id FROM delivery WHERE id = ? FOR UPDATE").use { statement ->
                            statement.setObject(1, repaired.id)
                            statement.executeQuery().use { rows -> rows.next() shouldBe true }
                        }

                        val migrationStarted = CountDownLatch(1)
                        val migration =
                            migrationExecutor.submit {
                                migrationStarted.countDown()
                                fixture.migrate()
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
    })

private data class LegacyCreate(
    val eventId: UUID = UUID.randomUUID(),
    val id: UUID = UUID.randomUUID(),
    val reference: String = "synthetic-reference-$id",
)

private fun PostgresTestFixture.migrateTo(target: String) {
    Flyway
        .configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:database.migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate()
}

private fun PostgresTestFixture.insertLegacyCreate(create: LegacyCreate) = insertLegacyDelivery(create, "CREATE", "ARBEIDSGIVERVARSEL")

private fun PostgresTestFixture.insertLegacyDelivery(
    create: LegacyCreate,
    operation: String,
    channel: String,
) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO inbox_message (event_id, content, reference, state) VALUES (?, '{}', ?, 'PROCESSED')",
            ).use { statement ->
                statement.setObject(1, create.eventId)
                statement.setString(2, create.reference)
                statement.executeUpdate() shouldBe 1
            }
        connection
            .prepareStatement(
                """
                INSERT INTO delivery (
                    id, inbox_event_id, reference, operation, channel, recipient_type, recipient_id, payload, state, attempt
                ) VALUES (?, ?, ?, ?, ?, 'VIRKSOMHET', 'synthetic-orgnummer', '{}', 'READY', 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, create.id)
                statement.setObject(2, create.eventId)
                statement.setString(3, create.reference)
                statement.setString(4, operation)
                statement.setString(5, channel)
                statement.executeUpdate() shouldBe 1
            }
    }
}

private fun PostgresTestFixture.deleteInbox(eventId: UUID) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("DELETE FROM inbox_message WHERE event_id = ?").use { statement ->
            statement.setObject(1, eventId)
            statement.executeUpdate() shouldBe 1
        }
    }
}

private fun PostgresTestFixture.setExternalId(
    deliveryId: UUID,
    externalId: String?,
) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("UPDATE delivery SET create_external_id = ? WHERE id = ?").use { statement ->
            statement.setString(1, externalId)
            statement.setObject(2, deliveryId)
            statement.executeUpdate() shouldBe 1
        }
    }
}

private fun PostgresTestFixture.createExternalId(deliveryId: UUID): String? =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("SELECT create_external_id FROM delivery WHERE id = ?").use { statement ->
            statement.setObject(1, deliveryId)
            statement.executeQuery().use { rows ->
                rows.next() shouldBe true
                rows.getString(1)
            }
        }
    }

private fun Connection.backendPid(): Int =
    prepareStatement("SELECT pg_backend_pid()").use { statement ->
        statement.executeQuery().use { rows ->
            rows.next() shouldBe true
            rows.getInt(1)
        }
    }

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
