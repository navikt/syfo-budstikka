package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.application.inbox.EffectuationResult
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.InboxMessageRepository
import no.nav.budstikka.application.port.TransactionRunner
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.FerdigstillMatch
import no.nav.budstikka.domain.decision.toFerdigstillMatch
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.brukervarselDraft
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.TransactionRunnerImpl
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

class InboxReferenceSerializationIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val inbox = InboxMessageRepositoryImpl(fixture.database)

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun effectuator(repository: InboxMessageRepository = inbox): EffectuateDecision =
            EffectuateDecision(
                TransactionRunnerImpl(fixture.database),
                repository,
                DeliveryRepositoryImpl(fixture.database),
            )

        suspend fun state(message: InboxMessage): String? =
            fixture.database.transact {
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq message.eventId }
                    .singleOrNull()
                    ?.get(InboxMessageTable.state)
            }

        suspend fun deliveryCount(message: InboxMessage): Long =
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq message.eventId }.count()
            }

        suspend fun referenceKey(reference: String): Long =
            fixture.database.transact {
                inbox.lockUnmaterializedCreatesForFerdigstillInTransaction(
                    requireNotNull(ferdigstill(reference).content.toFerdigstillMatch(reference)),
                )
                val connection = TransactionManager.current().connection.connection as Connection
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            """
                            SELECT (classid::bigint << 32) | objid::bigint FROM pg_locks
                            WHERE pid = pg_backend_pid() AND locktype = 'advisory' AND objsubid = 1 AND granted
                            """.trimIndent(),
                        ).use { result ->
                            check(result.next())
                            result.getLong(1).also { result.next() shouldBe false }
                        }
                }
            }

        test("a matching saveBatch waits for cancellation commit and then remains a processable later arrival") {
            val reference = "cancellation-wins"
            val existing = create(reference)
            val newcomer = create(reference)
            val cancellation = ferdigstill(reference)
            inbox.saveBatch(listOf(existing, cancellation))
            inbox.claim(10, 5.minutes, 10)
            val scanned = CountDownLatch(1)
            val release = CountDownLatch(1)
            val heldInbox =
                afterCandidateScan(inbox) {
                    it shouldBe listOf(existing.eventId)
                    scanned.countDown()
                    release.awaitBounded()
                }
            val writerName = UUID.randomUUID().toString()
            val writerInbox = InboxMessageRepositoryImpl(fixture.writerDatabase(writerName))

            coroutineScope {
                val cancellationTask =
                    async(Dispatchers.IO) {
                        effectuator(heldInbox).effectuate(cancellation, Decision.Processed(emptyList()))
                    }
                try {
                    scanned.awaitBounded()
                    val writer = async(Dispatchers.IO) { writerInbox.saveBatch(listOf(newcomer)) }
                    fixture.awaitLockWait(writerName, writer)
                    writer.isCompleted shouldBe false
                    state(newcomer) shouldBe null
                    release.countDown()
                    cancellationTask.await() shouldBe EffectuationResult.Completed
                    writer.await()
                } finally {
                    release.countDown()
                }
            }

            state(existing) shouldBe "PROCESSED"
            state(newcomer) shouldBe "RECEIVED"
            deliveryCount(existing) shouldBe 0L
            inbox.claim(10, 5.minutes, 10).map { it.eventId } shouldBe listOf(newcomer.eventId)
            effectuator().effectuate(newcomer, createDecision(reference)) shouldBe EffectuationResult.Completed
            deliveryCount(newcomer) shouldBe 1L
        }

        test("insertion winning the reference lock ignores the locked FERDIGSTILL retry and is then cancelled") {
            val reference = "insertion-wins"
            val cancellation = ferdigstill(reference)
            val newcomer = create(reference)
            inbox.saveBatch(listOf(cancellation))
            inbox.claim(10, 5.minutes, 10)
            val writerName = UUID.randomUUID().toString()
            val writerInbox = InboxMessageRepositoryImpl(fixture.writerDatabase(writerName))
            val cancellationName = UUID.randomUUID().toString()
            val cancellationDatabase = fixture.writerDatabase(cancellationName)
            val effectuate =
                EffectuateDecision(
                    TransactionRunnerImpl(cancellationDatabase),
                    InboxMessageRepositoryImpl(cancellationDatabase),
                    DeliveryRepositoryImpl(cancellationDatabase),
                )

            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { gate ->
                gate.autoCommit = false
                // SHARE blocks INSERT's table lock but permits effectuation's SELECT FOR UPDATE.
                gate.createStatement().use { it.execute("LOCK TABLE inbox_message IN SHARE MODE") }
                coroutineScope {
                    val writer = async(Dispatchers.IO) { writerInbox.saveBatch(listOf(cancellation, newcomer, newcomer)) }
                    try {
                        fixture.awaitLockWait(writerName, writer, lockType = "relation")
                        val cancellationTask =
                            async(Dispatchers.IO) { effectuate.effectuate(cancellation, Decision.Processed(emptyList())) }
                        fixture.awaitLockWait(cancellationName, cancellationTask)
                        state(newcomer) shouldBe null
                        gate.commit()
                        writer.await()
                        cancellationTask.await() shouldBe EffectuationResult.Completed
                    } finally {
                        gate.rollback()
                    }
                }
            }

            state(cancellation) shouldBe "PROCESSED"
            state(newcomer) shouldBe "PROCESSED"
            deliveryCount(newcomer) shouldBe 0L
            effectuator().effectuate(newcomer, createDecision(reference)) shouldBe EffectuationResult.Skipped
            deliveryCount(newcomer) shouldBe 0L
            fixture.database.transact { InboxMessageTable.selectAll().count() } shouldBe 2L
        }

        test("cancellation rollback releases the reference lock and preserves both existing and waiting writes") {
            val reference = "cancellation-rollback"
            val existing = create(reference)
            val newcomer = create(reference)
            val cancellation = ferdigstill(reference)
            inbox.saveBatch(listOf(existing, cancellation))
            inbox.claim(10, 5.minutes, 10)
            val changed = CountDownLatch(1)
            val release = CountDownLatch(1)
            val rollbackRunner =
                object : TransactionRunner {
                    override suspend fun <T> transaction(block: () -> T): T =
                        TransactionRunnerImpl(fixture.database).transaction {
                            block()
                            changed.countDown()
                            release.awaitBounded()
                            throw ExpectedRollback()
                        }
                }
            val effectuate = EffectuateDecision(rollbackRunner, inbox, DeliveryRepositoryImpl(fixture.database))
            val writerName = UUID.randomUUID().toString()
            val writerInbox = InboxMessageRepositoryImpl(fixture.writerDatabase(writerName))

            coroutineScope {
                val cancellationTask =
                    async(Dispatchers.IO) {
                        shouldThrow<ExpectedRollback> {
                            effectuate.effectuate(cancellation, Decision.Processed(emptyList()))
                        }
                    }
                try {
                    changed.awaitBounded()
                    val writer = async(Dispatchers.IO) { writerInbox.saveBatch(listOf(newcomer)) }
                    fixture.awaitLockWait(writerName, writer)
                    state(existing) shouldBe "CLAIMED"
                    state(newcomer) shouldBe null
                    release.countDown()
                    cancellationTask.await()
                    writer.await()
                } finally {
                    release.countDown()
                }
            }

            state(cancellation) shouldBe "CLAIMED"
            state(existing) shouldBe "CLAIMED"
            state(newcomer) shouldBe "RECEIVED"
            inbox.claim(10, 5.minutes, 10).map { it.eventId } shouldBe listOf(newcomer.eventId)
            effectuator().effectuate(existing, createDecision(reference)) shouldBe EffectuationResult.Completed
            effectuator().effectuate(newcomer, createDecision(reference)) shouldBe EffectuationResult.Completed
            deliveryCount(existing) shouldBe 1L
            deliveryCount(newcomer) shouldBe 1L
        }

        test("an unrelated reference commits while cancellation remains open") {
            val existing = create("held-reference")
            val cancellation = ferdigstill(existing.reference)
            val unrelated = create("unrelated-reference")
            inbox.saveBatch(listOf(existing, cancellation))
            inbox.claim(10, 5.minutes, 10)
            val scanned = CountDownLatch(1)
            val release = CountDownLatch(1)
            val heldInbox =
                afterCandidateScan(inbox) {
                    it shouldBe listOf(existing.eventId)
                    scanned.countDown()
                    release.awaitBounded()
                }

            coroutineScope {
                val cancellationTask =
                    async(Dispatchers.IO) {
                        effectuator(heldInbox).effectuate(cancellation, Decision.Processed(emptyList()))
                    }
                try {
                    scanned.awaitBounded()
                    withTimeout(5_000) { inbox.saveBatch(listOf(unrelated)) }
                    state(unrelated) shouldBe "RECEIVED"
                    state(existing) shouldBe "CLAIMED"
                    cancellationTask.isCompleted shouldBe false
                    release.countDown()
                    cancellationTask.await() shouldBe EffectuationResult.Completed
                } finally {
                    release.countDown()
                }
            }

            state(existing) shouldBe "PROCESSED"
            state(unrelated) shouldBe "RECEIVED"
        }

        test("opposite overlapping batches acquire distinct numeric keys in order before any insert") {
            val lower = create("batch-c")
            val upper = create("batch-b")
            val lowerKey = referenceKey(lower.reference)
            val upperKey = referenceKey(upper.reference)
            (lowerKey < upperKey) shouldBe true
            (lower.reference > upper.reference) shouldBe true
            val otherLower = create(lower.reference)
            val otherUpper = create(upper.reference)
            val firstName = UUID.randomUUID().toString()
            val secondName = UUID.randomUUID().toString()
            val firstInbox = InboxMessageRepositoryImpl(fixture.writerDatabase(firstName))
            val secondInbox = InboxMessageRepositoryImpl(fixture.writerDatabase(secondName))

            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { gate ->
                gate.autoCommit = false
                gate.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, upperKey)
                    statement.executeQuery().close()
                }
                coroutineScope {
                    val first = async(Dispatchers.IO) { firstInbox.saveBatch(listOf(upper, lower, upper)) }
                    try {
                        fixture.awaitLockWait(firstName, first)
                        fixture.assertLocksBeforeInsert(firstName, listOf(lowerKey to true, upperKey to false))
                        val second =
                            async(Dispatchers.IO) { secondInbox.saveBatch(listOf(otherLower, otherUpper, lower)) }
                        fixture.awaitLockWait(secondName, second)
                        fixture.assertLocksBeforeInsert(secondName, listOf(lowerKey to false))
                        gate.commit()
                        first.await()
                        second.await()
                    } finally {
                        gate.rollback()
                    }
                }
            }

            listOf(lower, upper, otherLower, otherUpper).forEach { state(it) shouldBe "RECEIVED" }
            fixture.database.transact { InboxMessageTable.selectAll().count() } shouldBe 4L
        }
    })

private fun afterCandidateScan(
    inbox: InboxMessageRepository,
    onCandidates: (List<UUID>) -> Unit,
): InboxMessageRepository =
    object : InboxMessageRepository by inbox {
        override fun lockUnmaterializedCreatesForFerdigstillInTransaction(match: FerdigstillMatch): List<UUID> =
            inbox.lockUnmaterializedCreatesForFerdigstillInTransaction(match).also(onCandidates)
    }

private fun create(reference: String): InboxMessage = inboxMessage(reference = reference, content = brukervarselDraft().content)

private class ExpectedRollback : RuntimeException("Deliberate cancellation rollback")

private fun ferdigstill(reference: String): InboxMessage =
    inboxMessage(reference = reference, content = BrukervarselInactivate(reference, TEST_SYKMELDT))

private fun createDecision(reference: String): Decision.Processed =
    Decision.Processed(listOf(brukervarselDraft().copy(reference = reference)))

private fun CountDownLatch.awaitBounded() {
    check(await(10, TimeUnit.SECONDS)) { "Timed out waiting for transaction coordination" }
}

private fun PostgresTestFixture.writerDatabase(applicationName: String): Database =
    Database.connect(
        "$jdbcUrl&ApplicationName=$applicationName&options=-c%20statement_timeout%3D10000",
        "org.postgresql.Driver",
        username,
        password,
    )

private fun PostgresTestFixture.assertLocksBeforeInsert(
    applicationName: String,
    expected: List<Pair<Long, Boolean>>,
) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT (l.classid::bigint << 32) | l.objid::bigint, l.granted
                FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE a.application_name = ? AND l.locktype = 'advisory' AND l.objsubid = 1
                ORDER BY 1
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, applicationName)
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) add(result.getLong(1) to result.getBoolean(2))
                    } shouldBe expected
                }
            }
        connection
            .prepareStatement(
                """
                SELECT count(*) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                WHERE a.application_name = ? AND l.relation = 'inbox_message'::regclass
                    AND l.mode = 'RowExclusiveLock'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, applicationName)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getLong(1) shouldBe 0L
                }
            }
    }
}

private suspend fun PostgresTestFixture.awaitLockWait(
    applicationName: String,
    task: Deferred<*>,
    lockType: String = "advisory",
) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid
                    WHERE a.application_name = ? AND l.locktype = ? AND NOT l.granted
                        AND (l.locktype <> 'advisory' OR l.objsubid = 1)
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, applicationName)
                statement.setString(2, lockType)
                withTimeout(5_000) {
                    while (true) {
                        val waiting =
                            statement.executeQuery().use { result ->
                                check(result.next())
                                result.getBoolean(1)
                            }
                        if (waiting) break
                        if (task.isCompleted) {
                            task.await()
                            error("Transaction completed instead of waiting for the held lock")
                        }
                        delay(10)
                    }
                }
            }
    }
}
