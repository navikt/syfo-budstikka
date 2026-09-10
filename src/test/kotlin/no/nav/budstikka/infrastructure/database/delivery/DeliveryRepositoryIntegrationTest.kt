package no.nav.budstikka.infrastructure.database.delivery

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CancellationException
import no.nav.budstikka.application.port.SourceSendGuardResult
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.brukervarselDraft
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class DeliveryRepositoryIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        suspend fun saveDraft(
            reference: String,
            draft: DeliveryDraft,
        ) {
            val inboxEventId = UUID.randomUUID()
            InboxMessageRepositoryImpl(fixture.database).saveBatch(listOf(inboxMessage(inboxEventId)))
            fixture.database.transact {
                DeliveryRepositoryImpl(fixture.database, fixture.dataSource).saveInTransaction(
                    inboxEventId,
                    listOf(draft.copy(reference = reference)),
                )
            }
        }

        suspend fun rowForReference(reference: String) =
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.single()
            }

        suspend fun expireLease(deliveryId: UUID) {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        suspend fun makePoison(
            deliveryId: UUID,
            attempt: Int,
        ) {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[DeliveryTable.attempt] = attempt
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        fun dependentDraft(sourceCreateDeliveryId: UUID): DeliveryDraft =
            microfrontendDraft().copy(
                operation = Operation.INACTIVATE,
                content = MicrofrontendDisable(TEST_SYKMELDT, "sykmeldt-overview"),
                sourceCreateDeliveryId = sourceCreateDeliveryId,
            )

        test("claim picks only requested channels and marks rows CLAIMED") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())
            saveDraft("bruker-ref", brukervarselDraft())

            val claimed =
                repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))

            claimed.shouldHaveSize(1)
            claimed.single().channel shouldBe Channel.MICROFRONTEND
            rowForReference("micro-ref")[DeliveryTable.state] shouldBe "CLAIMED"
            // Claiming reserves the row but does not spend a delivery attempt (#157).
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 0
            rowForReference("micro-ref")[DeliveryTable.nextAttemptTime] shouldNotBe null
            rowForReference("bruker-ref")[DeliveryTable.state] shouldBe "READY"
            rowForReference("bruker-ref")[DeliveryTable.attempt] shouldBe 0
        }

        test("claim reclaims a CLAIMED row after lease expiry") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())

            val initialClaim =
                repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
            initialClaim.shouldHaveSize(1)
            val deliveryId = initialClaim.single().id
            expireLease(deliveryId)

            val reclaimed =
                repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))

            reclaimed.shouldHaveSize(1)
            reclaimed.single().id shouldBe deliveryId
            // Reclaiming an expired lease does not spend an attempt either (#157).
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 0
        }

        test("beginAttempt spends one attempt and refuses once the budget is gone") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                    .single()
                    .id

            repository.beginAttempt(deliveryId, maxAttempts = 2) shouldBe true
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 1
            repository.beginAttempt(deliveryId, maxAttempts = 2) shouldBe true
            repository.beginAttempt(deliveryId, maxAttempts = 2) shouldBe false
            rowForReference("micro-ref")[DeliveryTable.attempt] shouldBe 2
        }

        test("beginAttempt refuses a row that is no longer CLAIMED") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                    .single()
                    .id
            repository.markSent(deliveryId) shouldBe true

            repository.beginAttempt(deliveryId, maxAttempts = 10) shouldBe false
        }

        test("markSent transitions a CLAIMED row to SENT") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(
                        limit = 10,
                        lease = lease,
                        maxAttempts = 10,
                        channels = setOf(Channel.MICROFRONTEND),
                    ).single()
                    .id

            repository.markSent(deliveryId) shouldBe true

            val row = rowForReference("micro-ref")
            row[DeliveryTable.state] shouldBe "SENT"
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldBe null
        }

        test("markFailed transitions a CLAIMED row to FAILED with reason") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("micro-ref", microfrontendDraft())
            val deliveryId =
                repository
                    .claim(
                        limit = 10,
                        lease = lease,
                        maxAttempts = 10,
                        channels = setOf(Channel.MICROFRONTEND),
                    ).single()
                    .id
            val reason = "Invalid microfrontend payload"

            repository.markFailed(deliveryId, reason) shouldBe true

            val row = rowForReference("micro-ref")
            row[DeliveryTable.state] shouldBe "FAILED"
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldBe reason
        }

        test("claim fails a poison delivery that reached maxAttempts instead of reclaiming it") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("poison-ref", microfrontendDraft())
            val maxAttempts = 3
            val channels = setOf(Channel.MICROFRONTEND)

            repeat(maxAttempts) {
                val claimed =
                    repository.claim(limit = 10, lease = lease, maxAttempts = maxAttempts, channels = channels)
                claimed.shouldHaveSize(1)
                // A real round spends an attempt before sending; claiming alone must not (#157).
                repository.beginAttempt(claimed.single().id, maxAttempts) shouldBe true
                expireLease(claimed.single().id)
            }

            repository
                .claim(limit = 10, lease = lease, maxAttempts = maxAttempts, channels = channels)
                .shouldHaveSize(0)

            val row = rowForReference("poison-ref")
            row[DeliveryTable.state] shouldBe "FAILED"
            row[DeliveryTable.attempt] shouldBe maxAttempts
            row[DeliveryTable.nextAttemptTime] shouldBe null
            row[DeliveryTable.errorMessage] shouldNotBe null
        }

        test("claim logs poison delivery with safe correlation fields") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("poison-ref", microfrontendDraft())
            val deliveryId = rowForReference("poison-ref")[DeliveryTable.id]
            makePoison(deliveryId, attempt = 2)
            val logbackLogger = LoggerFactory.getLogger(DeliveryRepositoryImpl::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                repository.claim(limit = 10, lease = lease, maxAttempts = 2, channels = setOf(Channel.MICROFRONTEND))
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            val event = appender.list.single { it.formattedMessage.contains("Failed poison delivery row") }
            event.formattedMessage shouldContain deliveryId.toString()
            event.formattedMessage shouldContain "poison-ref"
            event.formattedMessage shouldContain "CREATE"
            event.formattedMessage shouldContain "MICROFRONTEND"
            event.formattedMessage shouldContain "max_attempts=2"
        }

        test("a poison delivery does not block a healthy newer delivery on the same channel") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("poison-ref", microfrontendDraft())
            saveDraft("healthy-ref", microfrontendDraft())
            val poisonId = rowForReference("poison-ref")[DeliveryTable.id]
            makePoison(poisonId, attempt = 3)

            val claimed =
                repository.claim(limit = 1, lease = lease, maxAttempts = 3, channels = setOf(Channel.MICROFRONTEND))

            claimed.map { it.id } shouldBe listOf(rowForReference("healthy-ref")[DeliveryTable.id])
            rowForReference("poison-ref")[DeliveryTable.state] shouldBe "FAILED"
        }

        test("a pending source blocks its exact dependent without spending attempts or blocking unrelated work") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("source-ref", microfrontendDraft())
            val sourceId = rowForReference("source-ref")[DeliveryTable.id]
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq sourceId }) {
                    it[state] = DeliveryState.CLAIMED.name
                    it[attempt] = 1
                    it[nextAttemptTime] = Clock.System.now() + lease
                }
            }
            saveDraft("dependent-ref", dependentDraft(sourceId))
            saveDraft("unrelated-ref", microfrontendDraft())

            repository
                .claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                .map { it.reference }
                .shouldContainExactly("unrelated-ref")

            val dependent = rowForReference("dependent-ref")
            dependent[DeliveryTable.sourceCreateDeliveryId] shouldBe sourceId
            dependent[DeliveryTable.state] shouldBe DeliveryState.READY.name
            dependent[DeliveryTable.attempt] shouldBe 0
        }

        test("a SENT source releases its dependent and a FAILED source terminally fails it without an attempt") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("sent-source-ref", microfrontendDraft())
            val sentSourceId = rowForReference("sent-source-ref")[DeliveryTable.id]
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq sentSourceId }) {
                    it[state] = DeliveryState.SENT.name
                }
            }
            saveDraft("released-dependent-ref", dependentDraft(sentSourceId))

            repository
                .claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                .single()
                .sourceCreateDeliveryId shouldBe sentSourceId

            saveDraft("failed-source-ref", microfrontendDraft())
            val failedSourceId = rowForReference("failed-source-ref")[DeliveryTable.id]
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq failedSourceId }) {
                    it[state] = DeliveryState.FAILED.name
                }
            }
            saveDraft("failed-dependent-ref", dependentDraft(failedSourceId))

            repository.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
            val failedDependent = rowForReference("failed-dependent-ref")
            failedDependent[DeliveryTable.state] shouldBe DeliveryState.FAILED.name
            failedDependent[DeliveryTable.attempt] shouldBe 0
            failedDependent[DeliveryTable.errorMessage] shouldBe "Source CREATE delivery failed"
        }

        test("claim terminalizes failed-source dependents only in its channels and up to its limit") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)

            suspend fun failedSource(
                reference: String,
                draft: DeliveryDraft,
            ): UUID {
                saveDraft(reference, draft)
                return rowForReference(reference)[DeliveryTable.id].also { sourceId ->
                    fixture.database.transact {
                        DeliveryTable.update({ DeliveryTable.id eq sourceId }) {
                            it[state] = DeliveryState.FAILED.name
                        }
                    }
                }
            }

            val firstMicrofrontendSource = failedSource("first-microfrontend-source", microfrontendDraft())
            val secondMicrofrontendSource = failedSource("second-microfrontend-source", microfrontendDraft())
            val brukervarselSource = failedSource("brukervarsel-source", brukervarselDraft())
            saveDraft("first-microfrontend-dependent", dependentDraft(firstMicrofrontendSource))
            saveDraft("second-microfrontend-dependent", dependentDraft(secondMicrofrontendSource))
            saveDraft(
                "brukervarsel-dependent",
                brukervarselDraft().copy(
                    operation = Operation.INACTIVATE,
                    sourceCreateDeliveryId = brukervarselSource,
                ),
            )
            saveDraft("healthy-microfrontend", microfrontendDraft())

            repository
                .claim(limit = 1, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                .map { it.reference }
                .shouldContainExactly("healthy-microfrontend")

            val microfrontendStates =
                listOf("first-microfrontend-dependent", "second-microfrontend-dependent")
                    .map { rowForReference(it)[DeliveryTable.state] }
            microfrontendStates.count { it == DeliveryState.FAILED.name } shouldBe 1
            microfrontendStates.count { it == DeliveryState.READY.name } shouldBe 1
            rowForReference("brukervarsel-dependent")[DeliveryTable.state] shouldBe DeliveryState.READY.name
        }

        test("source send guard releases its PostgreSQL session lock on cancellation") {
            val repository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            saveDraft("guard-source-ref", microfrontendDraft())
            val claimed =
                repository
                    .claim(limit = 1, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                    .single()

            shouldThrow<CancellationException> {
                repository.withSourceSendGuard(claimed) { _ ->
                    throw CancellationException("test cancellation")
                }
            }

            repository.withSourceSendGuard(claimed) { _ -> } shouldBe SourceSendGuardResult.DISPATCHED
        }

        test("source send guard preserves handler failure and evicts its connection when unlock cleanup fails") {
            UnlockFailingDataSource(fixture).use { dataSource ->
                val repository = DeliveryRepositoryImpl(fixture.database, dataSource)
                saveDraft("guard-unlock-failure-ref", microfrontendDraft())
                val claimed =
                    repository
                        .claim(limit = 1, lease = lease, maxAttempts = 10, channels = setOf(Channel.MICROFRONTEND))
                        .single()

                val handlerFailure =
                    shouldThrow<IllegalStateException> {
                        repository.withSourceSendGuard(claimed) {
                            throw IllegalStateException("handler failed")
                        }
                    }

                handlerFailure.message shouldBe "handler failed"
                dataSource.unlockStatements.get() shouldBe 1
                handlerFailure.suppressed.single().message shouldBe "forced source guard unlock failure"
                dataSource.evictions.get() shouldBe 1
            }
        }
    })

private class UnlockFailingDataSource(
    fixture: PostgresTestFixture,
) : HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = fixture.jdbcUrl
            username = fixture.username
            password = fixture.password
            maximumPoolSize = 1
            minimumIdle = 0
        },
    ) {
    val evictions = AtomicInteger()
    val unlockStatements = AtomicInteger()

    override fun getConnection(): Connection =
        sourceUnlockFailingConnection(
            delegate = super.getConnection(),
            unlockStatements = unlockStatements,
        )

    override fun evictConnection(connection: Connection) {
        evictions.incrementAndGet()
    }
}

private fun sourceUnlockFailingConnection(
    delegate: Connection,
    unlockStatements: AtomicInteger,
): Connection =
    Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, arguments ->
        if (
            method.name == "prepareStatement" &&
            (arguments?.firstOrNull() as? String)?.contains("pg_advisory_unlock") == true
        ) {
            unlockStatements.incrementAndGet()
            failingUnlockStatement(delegate.prepareStatement(arguments.first() as String))
        } else {
            delegate.invoke(method, arguments)
        }
    } as Connection

private fun failingUnlockStatement(delegate: PreparedStatement): PreparedStatement =
    Proxy.newProxyInstance(
        PreparedStatement::class.java.classLoader,
        arrayOf(PreparedStatement::class.java),
    ) { _, method, arguments ->
        if (method.name == "executeQuery") {
            throw SQLException("forced source guard unlock failure")
        }
        delegate.invoke(method, arguments)
    } as PreparedStatement

private fun Any.invoke(
    method: java.lang.reflect.Method,
    arguments: Array<out Any?>?,
): Any? =
    try {
        method.invoke(this, *(arguments ?: emptyArray()))
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }
