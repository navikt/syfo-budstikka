package no.nav.budstikka.application.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.Operation
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins what happens when a lease expires while the worker that claimed the row is still working.
 *
 * The two sides of the pipeline behave differently, and the difference is the whole point:
 * effectuation writes its terminal CAS BEFORE any external effect, while delivery performs the
 * external send BEFORE its terminal CAS.
 */
class LeaseExpiryRaceIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        suspend fun expireInboxLease(eventId: UUID) {
            fixture.database.transact {
                InboxMessageTable.update({ InboxMessageTable.eventId eq eventId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        suspend fun expireDeliveryLease(deliveryId: UUID) {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq deliveryId }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        test("inbox: a peer reclaiming an expired lease does not produce a second set of delivery rows") {
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val effectuate =
                EffectuateDecision(
                    transactionRunner = TransactionRunnerImpl(fixture.database),
                    inboxMessageRepository = inbox,
                    deliveryRepository = DeliveryRepositoryImpl(fixture.database, fixture.dataSource),
                )
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            val message = inboxMessage(eventId)
            inbox.saveBatch(listOf(message))

            // Replica A claims and starts enrichment (PDL/KRR), which outlives the lease.
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)
            expireInboxLease(eventId)

            // Replica B reclaims the same row and enriches it a second time. The row stays CLAIMED,
            // so A's terminal CAS below still finds the state it guards on.
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)

            // Both replicas now effectuate the same message.
            effectuate.effectuate(message, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref"))))
            effectuate.effectuate(message, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref"))))

            // CLAIMED->PROCESSED is a one-shot transition, so only the first effectuation writes.
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq eventId }.count() shouldBe 1L
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq eventId }
                    .single()[InboxMessageTable.state] shouldBe "PROCESSED"
            }
        }

        test("delivery: a peer skips a guarded CREATE with attempts remaining and sends no duplicate") {
            val deliveries = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val inboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
            inbox.saveBatch(listOf(inboxMessage(inboxEventId)))
            val sourceDraft = microfrontendDraft(reference = "race-ref")
            fixture.database.transact {
                deliveries.saveInTransaction(inboxEventId, listOf(sourceDraft))
            }
            val sourceId =
                fixture.database.transact {
                    DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.single()[DeliveryTable.id]
                }
            val closeInboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
            inbox.saveBatch(listOf(inboxMessage(closeInboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(
                    closeInboxEventId,
                    listOf(
                        sourceDraft.copy(
                            operation = Operation.INACTIVATE,
                            content = MicrofrontendDisable(TEST_SYKMELDT, "sykmeldt-overview"),
                            sourceCreateDeliveryId = sourceId,
                        ),
                    ),
                )
            }

            val sends = mutableListOf<Operation>()
            val config =
                LeaseDrainConfig(
                    interval = 3.seconds,
                    batchSize = 25,
                    leaseDuration = lease,
                    leaseBudgetFraction = 0.8,
                    maxAttempts = 2,
                    maxConsecutiveItemFailures = 3,
                )

            fun workerWith(handler: ChannelHandler) =
                DeliveryWorker(
                    repository = deliveries,
                    handlers = mapOf(Channel.MICROFRONTEND to handler),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config = config,
                    metrics = NoDeliveryMetrics,
                )

            // Replica B simply sends whatever it claims.
            val replicaB =
                workerWith { delivery ->
                    sends += delivery.operation
                    DeliveryOutcome.Sent
                }

            // Replica A's send outlives its lease. B reclaims the row while A is paused under the
            // source guard, but skips dispatch on guard contention without spending the remaining
            // attempt or externally sending a duplicate.
            var peerHasRun = false
            val replicaA =
                workerWith { delivery ->
                    sends += delivery.operation
                    if (!peerHasRun) {
                        peerHasRun = true
                        expireDeliveryLease(delivery.id)
                        replicaB.runOnce()
                    }
                    DeliveryOutcome.Sent
                }

            replicaA.runOnce()
            // B can now claim and dispatch the close only after A durably marked CREATE SENT.
            replicaB.runOnce()

            sends shouldContainExactly listOf(Operation.CREATE, Operation.INACTIVATE)
            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq inboxEventId }
                    .single()[DeliveryTable.state] shouldBe "SENT"
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq closeInboxEventId }
                    .single()[DeliveryTable.state] shouldBe "SENT"
            }
        }

        test("delivery: a FAILED source terminally fails its dependent without handler dispatch") {
            val deliveries = DeliveryRepositoryImpl(fixture.database, fixture.dataSource)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val sourceInboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
            val sourceDraft = microfrontendDraft(reference = "failed-source")
            inbox.saveBatch(listOf(inboxMessage(sourceInboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(sourceInboxEventId, listOf(sourceDraft))
            }
            val sourceId =
                fixture.database.transact {
                    DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq sourceInboxEventId }.single()[DeliveryTable.id]
                }
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.id eq sourceId }) {
                    it[state] = "FAILED"
                }
            }
            val closeInboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b5")
            inbox.saveBatch(listOf(inboxMessage(closeInboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(
                    closeInboxEventId,
                    listOf(
                        sourceDraft.copy(
                            operation = Operation.INACTIVATE,
                            content = MicrofrontendDisable(TEST_SYKMELDT, "sykmeldt-overview"),
                            sourceCreateDeliveryId = sourceId,
                        ),
                    ),
                )
            }
            val handlerCalls = AtomicInteger()
            val worker =
                DeliveryWorker(
                    repository = deliveries,
                    handlers =
                        mapOf(
                            Channel.MICROFRONTEND to
                                ChannelHandler {
                                    handlerCalls.incrementAndGet()
                                    DeliveryOutcome.Sent
                                },
                        ),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config =
                        LeaseDrainConfig(
                            interval = 3.seconds,
                            batchSize = 25,
                            leaseDuration = lease,
                            leaseBudgetFraction = 0.8,
                            maxAttempts = 10,
                            maxConsecutiveItemFailures = 3,
                        ),
                    metrics = NoDeliveryMetrics,
                )

            worker.runOnce()

            handlerCalls.get() shouldBe 0
            fixture.database.transact {
                val close = DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq closeInboxEventId }.single()
                close[DeliveryTable.state] shouldBe "FAILED"
                close[DeliveryTable.attempt] shouldBe 0
                close[DeliveryTable.errorMessage] shouldBe "Source CREATE delivery failed"
            }
        }
    })
