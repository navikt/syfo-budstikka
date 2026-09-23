package no.nav.budstikka.application.worker

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.inbox.EffectuateDecision
import no.nav.budstikka.application.inbox.InboxMessageWorker
import no.nav.budstikka.application.port.ClaimToken
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.Decision
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.DecisionRule
import no.nav.budstikka.domain.decision.ResolvedRule
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.RecordingInboxMetrics
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.PostgresTransactionRunner
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.delivery.PostgresDeliveryRepository
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.PostgresInboxMessageRepository
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
 * Effectuation writes its terminal CAS before any external effect. Delivery sends externally before
 * its terminal CAS: fencing prevents an expired worker from recording an outcome after a peer
 * reclaims the row, but cannot prevent a second external send (delivery remains at-least-once).
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

        test("inbox: an expired worker cannot effectuate after a peer reclaims its lease") {
            val inbox = PostgresInboxMessageRepository(fixture.database)
            val effectuate =
                EffectuateDecision(
                    transactionRunner = PostgresTransactionRunner(fixture.database),
                    inboxMessageRepository = inbox,
                    deliveryRepository = PostgresDeliveryRepository(fixture.database),
                )
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
            inbox.saveBatch(listOf(inboxMessage(eventId)))

            // Replica A claims and starts enrichment (PDL/KRR), which outlives the lease.
            val claimA = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim
            expireInboxLease(eventId)

            // Replica B reclaims the same row with a new token while A is still working.
            val claimB = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claim
            claimB.token shouldNotBe claimA.token

            effectuate.effectuate(claimA, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref")))) shouldBe false
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq eventId }.count() shouldBe 0L
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.claimToken] shouldBe claimB.token.value
            }
            effectuate.effectuate(claimB, Decision.Processed(listOf(microfrontendDraft(reference = "race-ref")))) shouldBe true

            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq eventId }.count() shouldBe 1L
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "PROCESSED"
                row[InboxMessageTable.claimToken] shouldBe null
            }
        }

        test("delivery: an expired worker cannot record SENT while a peer holds the reclaimed claim") {
            val deliveries = PostgresDeliveryRepository(fixture.database)
            val inbox = PostgresInboxMessageRepository(fixture.database)
            val inboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
            inbox.saveBatch(listOf(inboxMessage(inboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(inboxEventId, listOf(microfrontendDraft(reference = "race-ref")))
            }

            val metrics = RecordingDeliveryMetrics()
            var peerClaimToken: UUID? = null
            val config =
                LeaseDrainConfig(
                    interval = 3.seconds,
                    batchSize = 25,
                    leaseDuration = lease,
                    leaseBudgetFraction = 0.8,
                    maxAttempts = 10,
                    maxConsecutiveItemFailures = 3,
                )

            // A's external send outlives its lease. B reclaims directly but has not recorded an
            // outcome yet, so the row remains CLAIMED when A attempts its terminal CAS.
            val replicaA =
                DeliveryWorker(
                    repository = deliveries,
                    handlers =
                        mapOf(
                            Channel.MICROFRONTEND to
                                ChannelHandler { delivery ->
                                    expireDeliveryLease(delivery.id)
                                    val peer =
                                        deliveries
                                            .claim(10, lease, 10, setOf(Channel.MICROFRONTEND))
                                            .single()
                                    peer.id shouldBe delivery.id
                                    peer.claimToken shouldNotBe delivery.claimToken
                                    peerClaimToken = peer.claimToken
                                    DeliveryOutcome.Sent
                                },
                        ),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config = config,
                    metrics = metrics,
                )

            replicaA.runOnce()

            val peerToken = checkNotNull(peerClaimToken)
            metrics.deliveryClaimLost[Channel.MICROFRONTEND]?.get() shouldBe 1
            metrics.deliverySent[Channel.MICROFRONTEND] shouldBe null
            val deliveryId =
                fixture.database.transact {
                    val row = DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.single()
                    row[DeliveryTable.state] shouldBe "CLAIMED"
                    row[DeliveryTable.claimToken] shouldBe peerToken
                    row[DeliveryTable.attempt] shouldBe 1
                    row[DeliveryTable.id]
                }
            deliveries.markFailed(deliveryId, peerToken, "peer outcome") shouldBe true
            fixture.database.transact {
                val row = DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.single()
                row[DeliveryTable.state] shouldBe "FAILED"
                row[DeliveryTable.errorMessage] shouldBe "peer outcome"
                row[DeliveryTable.claimToken] shouldBe null
            }
        }

        test("delivery: a lease expiring mid-send still sends twice, but only the peer records the outcome") {
            val deliveries = PostgresDeliveryRepository(fixture.database)
            val inbox = PostgresInboxMessageRepository(fixture.database)
            val inboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
            inbox.saveBatch(listOf(inboxMessage(inboxEventId)))
            fixture.database.transact {
                deliveries.saveInTransaction(inboxEventId, listOf(microfrontendDraft(reference = "race-ref")))
            }

            val sends = mutableListOf<UUID>()
            val metricsA = RecordingDeliveryMetrics()
            val metricsB = RecordingDeliveryMetrics()
            val config =
                LeaseDrainConfig(
                    interval = 3.seconds,
                    batchSize = 25,
                    leaseDuration = lease,
                    leaseBudgetFraction = 0.8,
                    maxAttempts = 10,
                    maxConsecutiveItemFailures = 3,
                )

            fun workerWith(
                metrics: RecordingDeliveryMetrics,
                handler: ChannelHandler,
            ) = DeliveryWorker(
                repository = deliveries,
                handlers = mapOf(Channel.MICROFRONTEND to handler),
                drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                config = config,
                metrics = metrics,
            )

            val replicaB =
                workerWith(metricsB) { delivery ->
                    sends += delivery.id
                    DeliveryOutcome.Sent
                }

            // A's send outlives its lease, so B reclaims, sends and records SENT before A returns.
            val replicaA =
                workerWith(metricsA) { delivery ->
                    sends += delivery.id
                    expireDeliveryLease(delivery.id)
                    replicaB.runOnce()
                    DeliveryOutcome.Sent
                }

            replicaA.runOnce()

            // Fencing cannot undo an external effect: delivery stays at-least-once.
            sends.size shouldBe 2
            sends.distinct().size shouldBe 1
            metricsB.deliverySent[Channel.MICROFRONTEND]?.get() shouldBe 1
            metricsA.deliverySent[Channel.MICROFRONTEND] shouldBe null
            metricsA.deliveryClaimLost[Channel.MICROFRONTEND]?.get() shouldBe 1
            fixture.database.transact {
                val row = DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq inboxEventId }.single()
                row[DeliveryTable.state] shouldBe "SENT"
                row[DeliveryTable.attempt] shouldBe 2
                row[DeliveryTable.claimToken] shouldBe null
            }
        }

        test("inbox worker: a decision computed after a peer reclaims the lease counts as claim lost") {
            val inbox = PostgresInboxMessageRepository(fixture.database)
            val eventId = UUID.fromString("00000000-0000-0000-0000-0000000000b4")
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            val metrics = RecordingInboxMetrics()
            var peerClaimToken: ClaimToken? = null

            // Enrichment outlives the lease; a peer reclaims the row before A's decision is persisted.
            val slowEnrichment =
                DecisionRule {
                    expireInboxLease(eventId)
                    peerClaimToken = inbox.claim(limit = 10, lease = lease, maxAttempts = 10).single().claimToken
                    ResolvedRule { deliveries -> Decision.Processed(deliveries) }
                }
            val worker =
                InboxMessageWorker(
                    repository = inbox,
                    effectuator =
                        EffectuateDecision(
                            transactionRunner = PostgresTransactionRunner(fixture.database),
                            inboxMessageRepository = inbox,
                            deliveryRepository = PostgresDeliveryRepository(fixture.database),
                        ),
                    decisionProcess = DecisionProcess(listOf(slowEnrichment)),
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
                    metrics = metrics,
                )

            worker.runOnce()

            metrics.decisionCasLostCount.get() shouldBe 1
            metrics.processedCount.get() shouldBe 0
            fixture.database.transact {
                DeliveryTable.selectAll().where { DeliveryTable.inboxEventId eq eventId }.count() shouldBe 0L
                val row = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                row[InboxMessageTable.state] shouldBe "CLAIMED"
                row[InboxMessageTable.claimToken] shouldBe checkNotNull(peerClaimToken).value
            }
        }
    })
