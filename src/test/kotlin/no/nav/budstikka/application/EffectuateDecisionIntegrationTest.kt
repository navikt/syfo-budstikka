package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.TransactionRunnerImpl
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class EffectuateDecisionIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun effectuator(): Pair<EffectuateDecision, InboxMessageRepositoryImpl> {
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val effectuate =
                EffectuateDecision(
                    transactionRunner = TransactionRunnerImpl(fixture.database),
                    inboxMessageRepository = inbox,
                    deliveryRepository = DeliveryRepositoryImpl(fixture.database),
                )
            return effectuate to inbox
        }

        suspend fun deliveryCount(inboxEventId: UUID): Long =
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.count()
            }

        suspend fun inboxState(eventId: UUID): String =
            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()[InboxMessageTable.state]
            }

        test("Processed commits delivery rows and inbox PROCESSED atomically") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(eventId, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1"))))

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }

        test("Failed writes no delivery rows and inbox FAILED with reason") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(eventId, Decision.Failed("boom"))

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "FAILED"
        }

        test("Dropped writes no delivery rows and inbox DROPPED") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(eventId, Decision.Dropped(DropReason.DEAD))

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "DROPPED"
        }

        test("a second Processed after the CAS is lost writes no extra delivery rows") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(eventId, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1"))))
            effectuate.effectuate(eventId, Decision.Processed(listOf(microfrontendDraft(reference = "ref-1"))))

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }
    })
