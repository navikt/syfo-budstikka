package no.nav.budstikka.application.inbox

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.BrukervarselInactivate
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DropReason
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.fakes.brukervarselDraft
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
import kotlin.time.Duration.Companion.seconds

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

        test("FERDIGSTILL persists the exact stored CREATE delivery as its INACTIVATE source") {
            val (effectuate, inbox) = effectuator()
            val reference = "ferdigstill-dependency"
            val sourceEventId = UUID.fromString("00000000-0000-0000-0000-0000000000a5")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000a6")
            val sourceMessage = inboxMessage(sourceEventId, reference = reference)
            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference = reference,
                    content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            inbox.saveBatch(listOf(sourceMessage, closeMessage))
            fixture.database.transact {
                DeliveryRepositoryImpl(fixture.database, fixture.dataSource).saveInTransaction(
                    sourceEventId,
                    listOf(brukervarselDraft().copy(reference = reference)),
                )
            }
            val sourceDeliveryId =
                fixture.database.transact {
                    DeliveryTable
                        .selectAll()
                        .where { DeliveryTable.inboxEventId eq sourceEventId }
                        .single()[DeliveryTable.id]
                }
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 1)

            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq closeEventId }
                    .single()[DeliveryTable.sourceCreateDeliveryId] shouldBe sourceDeliveryId
            }
        }

        test("FERDIGSTILL creates independent closes for older SENT and newer FAILED matching CREATEs") {
            val (effectuate, inbox) = effectuator()
            val deliveries = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            val reference = "duplicate-create-sent-and-failed"
            val olderSourceEventId = UUID.fromString("00000000-0000-0000-0000-0000000000a7")
            val newerSourceEventId = UUID.fromString("00000000-0000-0000-0000-0000000000a8")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000a9")
            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference = reference,
                    content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            inbox.saveBatch(
                listOf(
                    inboxMessage(olderSourceEventId, reference = reference),
                    inboxMessage(newerSourceEventId, reference = reference),
                    closeMessage,
                ),
            )
            fixture.database.transact {
                deliveries.saveInTransaction(
                    olderSourceEventId,
                    listOf(brukervarselDraft().copy(reference = reference)),
                )
                deliveries.saveInTransaction(
                    newerSourceEventId,
                    listOf(brukervarselDraft().copy(reference = reference)),
                )
            }
            val sourceIds =
                fixture.database.transact {
                    listOf(olderSourceEventId, newerSourceEventId).associateWith { eventId ->
                        DeliveryTable
                            .selectAll()
                            .where { DeliveryTable.inboxEventId eq eventId }
                            .single()[DeliveryTable.id]
                    }
                }
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq sourceIds.getValue(olderSourceEventId) }) {
                    it[state] = "SENT"
                }
                DeliveryTable.update({ DeliveryTable.id eq sourceIds.getValue(newerSourceEventId) }) {
                    it[state] = "FAILED"
                }
            }
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 2)

            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq closeEventId }
                    .map { it[DeliveryTable.sourceCreateDeliveryId] }
                    .shouldContainExactlyInAnyOrder(sourceIds.values)
            }

            val sentOperations = mutableListOf<Operation>()
            val metrics = RecordingDeliveryMetrics()
            DeliveryWorker(
                repository = deliveries,
                handlers =
                    mapOf(
                        Channel.BRUKERVARSEL to
                            ChannelHandler {
                                sentOperations += it.operation
                                DeliveryOutcome.Sent
                            },
                    ),
                drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                config =
                    LeaseDrainConfig(
                        interval = 1.seconds,
                        batchSize = 10,
                        leaseDuration = lease,
                        leaseBudgetFraction = 0.8,
                        maxAttempts = 10,
                        maxConsecutiveItemFailures = 3,
                    ),
                metrics = metrics,
            ).runOnce()

            sentOperations shouldContainExactlyInAnyOrder listOf(Operation.INACTIVATE)
            metrics.failedSourceDependencies.get() shouldBe 1
            fixture.database.transact {
                val dependents =
                    DeliveryTable
                        .selectAll()
                        .where { DeliveryTable.inboxEventId eq closeEventId }
                        .associateBy { it[DeliveryTable.sourceCreateDeliveryId] }
                dependents[sourceIds.getValue(olderSourceEventId)]?.get(DeliveryTable.state) shouldBe "SENT"
                dependents[sourceIds.getValue(newerSourceEventId)]?.get(DeliveryTable.state) shouldBe "FAILED"
                dependents[sourceIds.getValue(newerSourceEventId)]?.get(DeliveryTable.errorMessage) shouldBe
                    "Source CREATE delivery failed"
            }
        }

        test("FERDIGSTILL lets a SENT source close progress while a pending sibling close stays blocked") {
            val (effectuate, inbox) = effectuator()
            val deliveries = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            val reference = "duplicate-create-pending-and-sent"
            val sentSourceEventId = UUID.fromString("00000000-0000-0000-0000-0000000000aa")
            val pendingSourceEventId = UUID.fromString("00000000-0000-0000-0000-0000000000ab")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000ac")
            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference = reference,
                    content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            inbox.saveBatch(
                listOf(
                    inboxMessage(sentSourceEventId, reference = reference),
                    inboxMessage(pendingSourceEventId, reference = reference),
                    closeMessage,
                ),
            )
            fixture.database.transact {
                deliveries.saveInTransaction(sentSourceEventId, listOf(brukervarselDraft().copy(reference = reference)))
                deliveries.saveInTransaction(pendingSourceEventId, listOf(brukervarselDraft().copy(reference = reference)))
            }
            val sourceIds =
                fixture.database.transact {
                    listOf(sentSourceEventId, pendingSourceEventId).associateWith { eventId ->
                        DeliveryTable
                            .selectAll()
                            .where { DeliveryTable.inboxEventId eq eventId }
                            .single()[DeliveryTable.id]
                    }
                }
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq sourceIds.getValue(sentSourceEventId) }) {
                    it[state] = "SENT"
                }
                DeliveryTable.update({ DeliveryTable.id eq sourceIds.getValue(pendingSourceEventId) }) {
                    it[state] = "CLAIMED"
                    it[nextAttemptTime] =
                        kotlin.time.Clock.System
                            .now() + lease
                }
            }
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 2)

            val claim = deliveries.claim(limit = 10, lease = lease, maxAttempts = 10, channels = setOf(Channel.BRUKERVARSEL))
            claim.map { it.sourceCreateDeliveryId } shouldContainExactlyInAnyOrder
                listOf(sourceIds.getValue(sentSourceEventId))
            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq closeEventId }
                    .associateBy { it[DeliveryTable.sourceCreateDeliveryId] }
                    .getValue(sourceIds.getValue(pendingSourceEventId))[DeliveryTable.state] shouldBe "READY"
            }
        }

        test("FERDIGSTILL materializes valid duplicate CREATEs when an invalid sibling is stored") {
            val (effectuate, inbox) = effectuator()
            val deliveries = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            val reference = "duplicate-create-with-invalid-sibling"
            val validFirstEventId = UUID.fromString("00000000-0000-0000-0000-0000000000ad")
            val invalidEventId = UUID.fromString("00000000-0000-0000-0000-0000000000ae")
            val validLastEventId = UUID.fromString("00000000-0000-0000-0000-0000000000af")
            val closeEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b0")
            val closeMessage =
                inboxMessage(
                    closeEventId,
                    reference = reference,
                    content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                )
            inbox.saveBatch(
                listOf(
                    inboxMessage(validFirstEventId, reference = reference),
                    inboxMessage(invalidEventId, reference = reference),
                    inboxMessage(validLastEventId, reference = reference),
                    closeMessage,
                ),
            )
            fixture.database.transact {
                deliveries.saveInTransaction(validFirstEventId, listOf(brukervarselDraft().copy(reference = reference)))
                deliveries.saveInTransaction(
                    invalidEventId,
                    listOf(
                        brukervarselDraft().copy(
                            reference = reference,
                            content = BrukervarselInactivate(reference, TEST_SYKMELDT),
                        ),
                    ),
                )
                deliveries.saveInTransaction(validLastEventId, listOf(brukervarselDraft().copy(reference = reference)))
            }
            val sourceIds =
                fixture.database.transact {
                    listOf(validFirstEventId, invalidEventId, validLastEventId).associateWith { eventId ->
                        DeliveryTable
                            .selectAll()
                            .where { DeliveryTable.inboxEventId eq eventId }
                            .single()[DeliveryTable.id]
                    }
                }
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10)

            effectuate.effectuate(closeMessage, Decision.Processed(emptyList())) shouldBe
                EffectuationResult.FerdigstillWithDelivery(deliveryCount = 2, invalidStoredCreateCount = 1)

            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq closeEventId }
                    .map { it[DeliveryTable.sourceCreateDeliveryId] }
                    .shouldContainExactlyInAnyOrder(
                        listOf(
                            sourceIds.getValue(validFirstEventId),
                            sourceIds.getValue(validLastEventId),
                        ),
                    )
            }
        }
    })

private fun processed(): Decision.Processed = Decision.Processed(listOf(microfrontendDraft(reference = "ref-1")))
