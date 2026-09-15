package no.nav.budstikka.application.inbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.ArbeidsgivervarselInactivate
import no.nav.budstikka.contract.BrukervarselCreate
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.contract.Varseltype
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.fakes.TEST_SYKMELDT
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
import org.jetbrains.exposed.v1.jdbc.update
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
                    deliveryRepository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource),
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
            val message = inboxMessage(eventId)
            inbox.saveBatch(listOf(message))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(message, processed()) shouldBe EffectuationResult.Completed

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }

        test("Failed writes no delivery rows and inbox FAILED with reason") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
            val message = inboxMessage(eventId)
            inbox.saveBatch(listOf(message))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(message, Decision.Failed("boom")) shouldBe EffectuationResult.Completed

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "FAILED"
        }

        test("Dropped writes no delivery rows and inbox DROPPED") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
            val message = inboxMessage(eventId)
            inbox.saveBatch(listOf(message))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(message, Decision.Dropped(DropReason.DEAD)) shouldBe EffectuationResult.Completed

            deliveryCount(eventId) shouldBe 0L
            inboxState(eventId) shouldBe "DROPPED"
        }

        test("a second Processed after the CAS is lost writes no extra delivery rows") {
            val (effectuate, inbox) = effectuator()
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000a4")
            val message = inboxMessage(eventId)
            inbox.saveBatch(listOf(message))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(message, processed()) shouldBe EffectuationResult.Completed
            effectuate.effectuate(message, processed()) shouldBe EffectuationResult.Skipped

            deliveryCount(eventId) shouldBe 1L
            inboxState(eventId) shouldBe "PROCESSED"
        }

        test("Arbeidsgivervarsel FERDIGSTILL carries the stored external id and source delivery id") {
            val (effectuate, inbox) = effectuator()
            val reference = "arbeidsgivervarsel-with-external-id"
            val createEventId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
            val create = arbeidsgivervarselCreate()
            val createMessage = inboxMessage(createEventId, reference, create)
            inbox.saveBatch(listOf(createMessage))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)
            effectuate.effectuate(
                createMessage,
                Decision.Processed(
                    listOf(
                        DeliveryDraft(
                            reference = reference,
                            operation = Operation.CREATE,
                            channel = Channel.ARBEIDSGIVERVARSEL,
                            recipient = Recipient.Virksomhet(TEST_ORGNUMMER),
                            content = create,
                        ),
                    ),
                ),
            ) shouldBe EffectuationResult.Completed
            val sourceDeliveryId =
                fixture.database.transact {
                    DeliveryTable
                        .selectAll()
                        .where { DeliveryTable.inboxEventId eq createEventId }
                        .single()[DeliveryTable.id]
                }

            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference,
                    ArbeidsgivervarselInactivate(reference, TEST_ORGNUMMER),
                )
            inbox.saveBatch(listOf(closeMessage))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)

            fixture.database.transact {
                val derived =
                    DeliveryTable
                        .selectAll()
                        .where { DeliveryTable.inboxEventId eq closeEventId }
                        .single()
                derived[DeliveryTable.operation] shouldBe Operation.INACTIVATE.name
                derived[DeliveryTable.externalId] shouldBe createEventId.toString()
                derived[DeliveryTable.sourceCreateDeliveryId] shouldBe sourceDeliveryId
            }
        }

        test("historical Arbeidsgivervarsel CREATE without external_id fails closed for FERDIGSTILL") {
            val (effectuate, inbox) = effectuator()
            val reference = "arbeidsgivervarsel-without-external-id"
            val createEventId = UUID.fromString("00000000-0000-0000-0000-0000000000c3")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000c4")
            val create = arbeidsgivervarselCreate()
            val createMessage = inboxMessage(createEventId, reference, create)
            inbox.saveBatch(listOf(createMessage))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)
            effectuate.effectuate(
                createMessage,
                Decision.Processed(
                    listOf(
                        DeliveryDraft(
                            reference = reference,
                            operation = Operation.CREATE,
                            channel = Channel.ARBEIDSGIVERVARSEL,
                            recipient = Recipient.Virksomhet(TEST_ORGNUMMER),
                            content = create,
                        ),
                    ),
                ),
            ) shouldBe EffectuationResult.Completed
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.inboxEventId eq createEventId }) {
                    it[DeliveryTable.externalId] = null
                }
            }

            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference,
                    ArbeidsgivervarselInactivate(reference, TEST_ORGNUMMER),
                )
            inbox.saveBatch(listOf(closeMessage))
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithInvalidStoredCreate()
            deliveryCount(closeEventId) shouldBe 0L
        }

        test("FERDIGSTILL returns the number of unmaterialized CREATE inbox rows it cancels") {
            val (effectuate, inbox) = effectuator()
            val reference = "ferdigstill-cancellation-only"
            val unmaterializedCreateEventIds =
                listOf(
                    UUID.fromString("00000000-0000-0000-0000-0000000000b2"),
                    UUID.fromString("00000000-0000-0000-0000-0000000000b3"),
                )
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference = reference,
                    content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            inbox.saveBatch(
                unmaterializedCreateEventIds.map { eventId ->
                    inboxMessage(
                        eventId,
                        reference = reference,
                        content = BrukervarselCreate(TEST_SYKMELDT, Varseltype.BESKJED, "waiting create"),
                    )
                } + closeMessage,
            )
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithCancellation(cancelledCreateCount = 2)

            inboxState(closeEventId) shouldBe "PROCESSED"
            unmaterializedCreateEventIds.forEach { eventId ->
                inboxState(eventId) shouldBe "PROCESSED"
            }
            deliveryCount(closeEventId) shouldBe 0L
        }
    })

private fun processed(): Decision.Processed = Decision.Processed(listOf(microfrontendDraft(reference = "ref-1")))

private fun arbeidsgivervarselCreate() =
    ArbeidsgivervarselCreate(
        orgnummer = TEST_ORGNUMMER,
        recipient = AltinnResource("synthetic-resource"),
        tag = "synthetic-tag",
        text = "synthetic text",
        link = "https://example.test/synthetic",
    )
