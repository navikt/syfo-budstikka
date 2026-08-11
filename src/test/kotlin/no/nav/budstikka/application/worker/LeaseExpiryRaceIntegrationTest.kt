package no.nav.budstikka.application.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.application.port.NoDispatchMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
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
                    deliveryRepository = DeliveryRepositoryImpl(fixture.database),
                )
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            inbox.saveBatch(listOf(inboxMessage(eventId)))

            // Replica A claims and starts enrichment (PDL/KRR), which outlives the lease.
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)
            expireInboxLease(eventId)

            // Replica B reclaims the same row and enriches it a second time. The row stays CLAIMED,
            // so A's terminal CAS below still finds the state it guards on.
            inbox.claim(limit = 10, lease = lease, maxAttempts = 10).shouldHaveSize(1)

            // Both replicas now effectuate the same message.
            effectuate.effectuate(eventId, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref"))))
            effectuate.effectuate(eventId, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref"))))

            // CLAIMED->PROCESSED is a one-shot transition, so only the first effectuation writes.
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq eventId }.count() shouldBe 1L
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq eventId }
                    .single()[InboxMessageTable.state] shouldBe "PROCESSED"
            }
        }

        test("delivery: a lease expiring mid-send makes a peer send the same delivery a second time") {
            val deliveries = DeliveryRepositoryImpl(fixture.database)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val inboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
            inbox.saveBatch(listOf(inboxMessage(inboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(inboxEventId, listOf(microfrontendDraft(reference = "race-ref")))
            }

            val sends = mutableListOf<UUID>()
            val config =
                LeaseDrainConfig(
                    interval = 3.seconds,
                    batchSize = 25,
                    leaseDuration = lease,
                    leaseBudgetFraction = 0.8,
                    maxAttempts = 10,
                    maxConsecutiveItemFailures = 3,
                )

            fun workerWith(handler: ChannelHandler) =
                DeliveryWorker(
                    repository = deliveries,
                    handlers = mapOf(Channel.MICROFRONTEND to handler),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config = config,
                    metrics = NoDispatchMetrics,
                )

            // Replica B simply sends whatever it claims.
            val replicaB =
                workerWith { delivery ->
                    sends += delivery.id
                    DeliveryOutcome.Sent
                }

            // Replica A's send outlives its lease: the row becomes claimable again while A is still
            // inside handler.handle(), so B reclaims and sends before A reaches markSent.
            var peerHasRun = false
            val replicaA =
                workerWith { delivery ->
                    sends += delivery.id
                    if (!peerHasRun) {
                        peerHasRun = true
                        expireDeliveryLease(delivery.id)
                        replicaB.runOnce()
                    }
                    DeliveryOutcome.Sent
                }

            replicaA.runOnce()

            // The external side effect happened twice for one delivery row; the terminal CAS only
            // decided which replica got to record it.
            sends shouldHaveSize 2
            sends.distinct() shouldHaveSize 1
            fixture.database.transact {
                val row = DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.single()
                row[DeliveryTable.state] shouldBe "SENT"
                row[DeliveryTable.attempt] shouldBe 2
            }
        }
    })
