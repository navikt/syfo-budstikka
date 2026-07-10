package no.nav.budstikka.application

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.domain.dispatch.MicrofrontendEnable
import no.nav.budstikka.domain.dispatch.PersonIdentifier
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.ExposedTransactionRunner
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Duration
import java.util.UUID

class EffectuateDecisionIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = Duration.ofMinutes(5)

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun effectuator(): Pair<EffectuateDecision, InboxMessageRepositoryImpl> {
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val effectuate =
                EffectuateDecision(
                    transactionRunner = ExposedTransactionRunner(fixture.database),
                    inboxMessageRepository = inbox,
                    deliveryRepository = DeliveryRepositoryImpl(),
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

        fun draft(): DeliveryDraft {
            val ident = PersonIdentifier("12345678901")
            return DeliveryDraft(
                reference = "ref-1",
                operation = Operation.CREATE,
                channel = Channel.MICROFRONTEND,
                recipient = Recipient.Person(ident),
                content = MicrofrontendEnable(ident, "syfo-mikrofrontend"),
            )
        }

        test("Processed commits delivery rows and inbox PROCESSED atomically") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
            inbox.save(eventId, """{"eventId":"$eventId"}""")
            inbox.claim(limit = 10, lease = lease)

            effectuate.effectuate(eventId, Decision.Processed(listOf(draft())))

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }

        test("Failed writes no delivery rows and inbox FAILED with reason") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            inbox.save(eventId, """{"eventId":"$eventId"}""")
            inbox.claim(limit = 10, lease = lease)

            effectuate.effectuate(eventId, Decision.Failed("boom"))

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "FAILED"
        }

        test("Dropped writes no delivery rows and inbox DROPPED") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
            inbox.save(eventId, """{"eventId":"$eventId"}""")
            inbox.claim(limit = 10, lease = lease)

            effectuate.effectuate(eventId, Decision.Dropped(DropReason.DEAD))

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "DROPPED"
        }

        test("a second Processed after the CAS is lost writes no extra delivery rows") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
            inbox.save(eventId, """{"eventId":"$eventId"}""")
            inbox.claim(limit = 10, lease = lease)

            effectuate.effectuate(eventId, Decision.Processed(listOf(draft())))
            effectuate.effectuate(eventId, Decision.Processed(listOf(draft())))

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }
    })
