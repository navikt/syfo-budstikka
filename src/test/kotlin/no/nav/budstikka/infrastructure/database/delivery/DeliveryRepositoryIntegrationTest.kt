package no.nav.budstikka.infrastructure.database.delivery

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.fakes.brukervarselDraft
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.PostgresInboxMessageRepository
import no.nav.budstikka.testsupport.renderedLogData
import no.nav.budstikka.testsupport.structuredFields
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
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
        ): UUID {
            val inboxEventId = UUID.randomUUID()
            PostgresInboxMessageRepository(fixture.database).saveBatch(listOf(inboxMessage(inboxEventId)))
            fixture.database.transact {
                PostgresDeliveryRepository(fixture.database).saveInTransaction(
                    inboxEventId,
                    listOf(draft.copy(reference = reference)),
                )
            }
            return inboxEventId
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

        test("claim picks only requested channels and marks rows CLAIMED") {
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
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
            val repository = PostgresDeliveryRepository(fixture.database)
            saveDraft("poison-ref", microfrontendDraft())
            val deliveryId = rowForReference("poison-ref")[DeliveryTable.id]
            makePoison(deliveryId, attempt = 2)
            val logbackLogger = LoggerFactory.getLogger(PostgresDeliveryRepository::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                repository.claim(limit = 10, lease = lease, maxAttempts = 2, channels = setOf(Channel.MICROFRONTEND))
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Failed poison delivery row") }) {
                level shouldBe Level.WARN
                val fields = structuredFields()
                fields["event_type"] shouldBe DeliveryRepositoryLogEvents.poisonRowFailed.name
                fields["operation"] shouldBe "delivery.fail_poison_row"
                fields[MdcKeys.DELIVERY_ID] shouldBe deliveryId.toString()
                fields[MdcKeys.REFERENCE] shouldBe "poison-ref"
                fields[MdcKeys.DELIVERY_OPERATION] shouldBe "CREATE"
                fields[MdcKeys.DELIVERY_CHANNEL] shouldBe "MICROFRONTEND"
                fields[MdcKeys.MAX_ATTEMPTS] shouldBe 2
            }
        }

        test("poison delivery log omits a null retained inbox event id") {
            val repository = PostgresDeliveryRepository(fixture.database)
            val inboxEventId = saveDraft("retained-poison-ref", microfrontendDraft())
            val deliveryId = rowForReference("retained-poison-ref")[DeliveryTable.id]
            makePoison(deliveryId, attempt = 2)
            fixture.database.transact {
                InboxMessageTable.deleteWhere { InboxMessageTable.eventId eq inboxEventId }
            }
            rowForReference("retained-poison-ref")[DeliveryTable.inboxEventId] shouldBe null

            val logbackLogger = LoggerFactory.getLogger(PostgresDeliveryRepository::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logbackLogger.addAppender(appender)
            try {
                repository.claim(limit = 10, lease = lease, maxAttempts = 2, channels = setOf(Channel.MICROFRONTEND))
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            with(appender.list.single { it.formattedMessage.contains("Failed poison delivery row") }) {
                level shouldBe Level.WARN
                val fields = structuredFields()
                fields["event_type"] shouldBe DeliveryRepositoryLogEvents.poisonRowFailed.name
                fields["operation"] shouldBe "delivery.fail_poison_row"
                fields[MdcKeys.DELIVERY_ID] shouldBe deliveryId.toString()
                fields[MdcKeys.REFERENCE] shouldBe "retained-poison-ref"
                fields[MdcKeys.DELIVERY_OPERATION] shouldBe "CREATE"
                fields[MdcKeys.DELIVERY_CHANNEL] shouldBe "MICROFRONTEND"
                fields[MdcKeys.DELIVERY_COUNT] shouldBe 2
                fields[MdcKeys.MAX_ATTEMPTS] shouldBe 2
                fields.containsKey(MdcKeys.EVENT_ID) shouldBe false
                fields.containsKey("logging_context_invalid") shouldBe false
                renderedLogData() shouldNotContain "sykmeldt-overview"
            }
        }

        test("a poison delivery does not block a healthy newer delivery on the same channel") {
            val repository = PostgresDeliveryRepository(fixture.database)
            saveDraft("poison-ref", microfrontendDraft())
            saveDraft("healthy-ref", microfrontendDraft())
            val poisonId = rowForReference("poison-ref")[DeliveryTable.id]
            makePoison(poisonId, attempt = 3)

            val claimed =
                repository.claim(limit = 1, lease = lease, maxAttempts = 3, channels = setOf(Channel.MICROFRONTEND))

            claimed.map { it.id } shouldBe listOf(rowForReference("healthy-ref")[DeliveryTable.id])
            rowForReference("poison-ref")[DeliveryTable.state] shouldBe "FAILED"
        }
    })
