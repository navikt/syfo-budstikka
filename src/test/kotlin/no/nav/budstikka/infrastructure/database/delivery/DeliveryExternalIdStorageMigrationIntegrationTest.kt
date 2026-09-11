package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.toDeliveryDraft
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.brukervarselDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class DeliveryExternalIdStorageMigrationIntegrationTest :
    FunSpec({
        test("V11 repairs a legacy writer's null identities without changing delivery state or attempts") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("10")
                val create = LegacyCreate()
                fixture.insertLegacyCreate(create)
                val dependentId = fixture.insertDependent(create, externalId = null, attempt = 2)
                val before =
                    mapOf(
                        create.id to
                            DeliverySnapshot(
                                id = create.id,
                                inboxEventId = create.eventId,
                                externalId = null,
                                sourceId = null,
                                state = "READY",
                                attempt = 0,
                                error = null,
                            ),
                        dependentId to
                            DeliverySnapshot(
                                id = dependentId,
                                inboxEventId = null,
                                externalId = null,
                                sourceId = create.id,
                                state = "READY",
                                attempt = 2,
                                error = null,
                            ),
                    )
                fixture.snapshot() shouldBe before

                fixture.migrate()

                val repaired = before.mapValues { (_, row) -> row.copy(externalId = create.eventId.toString()) }
                fixture.snapshot() shouldBe repaired
                fixture.deleteInbox(create.eventId)
                fixture.snapshot() shouldBe repaired.mapValues { (_, row) -> row.copy(inboxEventId = null) }
            }
        }

        test("V11 storage repair preserves known and terminal identities and its exact UPDATEs are idempotent") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("10")
                val repairable = LegacyCreate(reference = "synthetic-shared-reference")
                val known = LegacyCreate(reference = repairable.reference)
                val ambiguous = LegacyCreate()
                val unresolved = LegacyCreate()
                listOf(repairable, known, ambiguous, unresolved).forEach { fixture.insertLegacyCreate(it) }
                fixture.setExternalId(known.id, "synthetic-known-external-id")
                fixture.setExternalId(ambiguous.id, ambiguous.id.toString())
                listOf(known, ambiguous, unresolved).forEach { fixture.deleteInbox(it.eventId) }

                val pendingNull = fixture.insertDependent(repairable, externalId = null, attempt = 2)
                val pendingGuess =
                    fixture.insertDependent(repairable, externalId = repairable.id.toString(), state = "CLAIMED", attempt = 3)
                val pendingKnownSource = fixture.insertDependent(known, externalId = null)
                val pendingAmbiguous = fixture.insertDependent(ambiguous, externalId = ambiguous.id.toString())
                fixture.insertDependent(unresolved, externalId = null)
                fixture.insertDependent(repairable, externalId = "synthetic-known-dependent-id")
                listOf("SENT", "FAILED").forEach { state ->
                    listOf(null, repairable.id.toString(), "synthetic-terminal-id").forEachIndexed { index, externalId ->
                        fixture.insertDependent(repairable, externalId, state, attempt = 4 + index)
                    }
                }
                val before = fixture.snapshot()
                val repairedIds =
                    mapOf(
                        repairable.id to repairable.eventId.toString(),
                        ambiguous.id to null,
                        pendingNull to repairable.eventId.toString(),
                        pendingGuess to repairable.eventId.toString(),
                        pendingKnownSource to "synthetic-known-external-id",
                        pendingAmbiguous to null,
                    )
                val expected =
                    before.mapValues { (id, row) ->
                        if (id in repairedIds) row.copy(externalId = repairedIds[id]) else row
                    }
                (expected == before) shouldBe false

                fixture.dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        val repairStatements = v11RepairStatements()
                        val updated =
                            repairStatements.sumOf { sql ->
                                connection.prepareStatement(sql).use { it.executeUpdate() }
                            }
                        (updated > 0) shouldBe true
                        val afterFirstRepair = connection.snapshot()
                        afterFirstRepair shouldBe expected
                        repairStatements.forEach { sql ->
                            connection.prepareStatement(sql).use { it.executeUpdate() }
                        }
                        connection.snapshot() shouldBe afterFirstRepair
                    } finally {
                        connection.rollback()
                    }
                }
                fixture.snapshot() shouldBe before
                fixture.migrate()
                fixture.snapshot() shouldBe expected
            }
        }

        test("post-V11 trigger leaves other channel CREATE and ARBEIDSGIVERVARSEL INACTIVATE identities untouched") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate()
                val create = syntheticCreate()
                val drafts =
                    listOf(
                        brukervarselDraft(),
                        requireNotNull(create.toDeliveryDraft("synthetic-inactivate")).copy(operation = Operation.INACTIVATE),
                    )
                drafts.forEach { draft ->
                    val inboxContent =
                        if (draft.operation == Operation.INACTIVATE) {
                            ArbeidsgivervarselInactivate(draft.reference, TEST_ORGNUMMER)
                        } else {
                            draft.content
                        }
                    val message = InboxMessage(UUID.randomUUID(), draft.reference, inboxContent)
                    val id = fixture.insertTriggerProbe(message, draft)
                    fixture.snapshot().getValue(id).let { row ->
                        row.inboxEventId shouldBe message.eventId
                        row.externalId shouldBe null
                        row.state shouldBe "READY"
                        row.attempt shouldBe 0
                    }
                }
            }
        }

        test("a legacy writer cannot insert between V11 repair and trigger installation") {
            PostgresTestFixture().use { raceFixture ->
                val repairedEventId = UUID.fromString("00000000-0000-0000-0000-000000000911")
                val repairedDeliveryId = UUID.fromString("00000000-0000-0000-0000-000000000912")
                val concurrentEventId = UUID.fromString("00000000-0000-0000-0000-000000000913")
                val concurrentDeliveryId = UUID.fromString("00000000-0000-0000-0000-000000000914")
                raceFixture.migrateTo("10")
                raceFixture.insertLegacyCreate(repairedEventId, repairedDeliveryId)

                DriverManager.getConnection(raceFixture.jdbcUrl, raceFixture.username, raceFixture.password).use { blocker ->
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
                            statement.setObject(1, repairedDeliveryId)
                            statement.executeQuery().use { resultSet -> resultSet.next() shouldBe true }
                        }

                        val migrationStarted = CountDownLatch(1)
                        val migration =
                            migrationExecutor.submit {
                                migrationStarted.countDown()
                                raceFixture.migrate()
                            }
                        migrationStarted.awaitOrFail("V11 migration did not start")
                        raceFixture.awaitWaitingDatabaseLock(blockerPid)

                        val writer =
                            writerExecutor.submit {
                                raceFixture.insertLegacyCreate(concurrentEventId, concurrentDeliveryId)
                            }

                        raceFixture.awaitWriterAtMigrationBoundary(writer)
                        writer.isDone shouldBe false
                        blocker.commit()
                        migration.get(10, TimeUnit.SECONDS)
                        writer.get(10, TimeUnit.SECONDS)

                        raceFixture.createExternalId(repairedDeliveryId) shouldBe repairedEventId.toString()
                        raceFixture.createExternalId(concurrentDeliveryId) shouldBe concurrentEventId.toString()
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

private fun PostgresTestFixture.insertLegacyCreate(
    eventId: UUID,
    deliveryId: UUID,
) = insertLegacyCreate(LegacyCreate(eventId = eventId, id = deliveryId))

private fun PostgresTestFixture.setExternalId(
    id: UUID,
    externalId: String?,
) {
    dataSource.connection.use { connection ->
        connection.prepareStatement("UPDATE delivery SET create_external_id = ? WHERE id = ?").use { statement ->
            statement.setString(1, externalId)
            statement.setObject(2, id)
            statement.executeUpdate() shouldBe 1
        }
    }
}

private fun v11RepairStatements(): List<String> {
    val migration =
        checkNotNull(
            DeliveryExternalIdStorageMigrationIntegrationTest::class.java.getResourceAsStream(
                "/database.migration/V11__repair_arbeidsgivervarsel_create_external_id.sql",
            ),
        ).bufferedReader().use { it.readText() }
    val repairStart = migration.indexOf("\nUPDATE delivery\n")
    check(repairStart > migration.indexOf("EXECUTE FUNCTION")) { "Expected repair SQL after the V11 trigger DDL" }
    return migration
        .substring(repairStart)
        .split(';')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .also { it.size shouldBe 3 }
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
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        if (resultSet.getBoolean(1)) return
                    }
                }
        }
        Thread.sleep(10)
    }
    error("V11 repair did not wait for the target row lock")
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
                    statement.executeQuery().use { resultSet ->
                        resultSet.next()
                        if (resultSet.getBoolean(1)) return
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
