package no.nav.budstikka.application.worker

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.delivery.ChannelHandler
import no.nav.budstikka.application.delivery.DeliveryOutcome
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.delivery.NoDeliveryMetrics
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.fakes.microfrontendDraft
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryRepositoryImpl
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the meaning of `attempt` (#157): it counts durable authorisations to START processing, not
 * claims. `claim` hands out a whole batch at once, and the drainer may leave the tail untouched
 * (spent lease budget, or batch abort after consecutive failures). If claiming spent an attempt,
 * the poison gate would terminate rows that were never handed to a handler.
 */
class PoisonAttemptSemanticsIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes
        val maxAttempts = 3

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        suspend fun expireAllLeases() {
            fixture.database.transact {
                DeliveryTable.update({ DeliveryTable.state eq "CLAIMED" }) {
                    it[nextAttemptTime] = Clock.System.now() - 1.minutes
                }
            }
        }

        test("an aborted batch never spends attempts on rows that no handler touched") {
            val deliveries = DeliveryRepositoryImpl(fixture.database)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val inboxEventId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
            inbox.saveBatch(listOf(inboxMessage(inboxEventId)))

            // Three rows that always fail, then two healthy rows queued behind them.
            val poisonRefs = listOf("poison-1", "poison-2", "poison-3")
            val healthyRefs = listOf("healthy-1", "healthy-2")
            fixture.database.transact {
                deliveries.saveInTransaction(
                    inboxEventId,
                    (poisonRefs + healthyRefs).map { microfrontendDraft(reference = it) },
                )
            }

            val handledRefs = mutableListOf<String>()
            val worker =
                DeliveryWorker(
                    repository = deliveries,
                    handlers =
                        mapOf(
                            Channel.MICROFRONTEND to
                                ChannelHandler { delivery ->
                                    handledRefs += delivery.reference
                                    if (delivery.reference in poisonRefs) {
                                        error("deterministic failure for ${delivery.reference}")
                                    }
                                    DeliveryOutcome.Sent
                                },
                        ),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 1.0, maxConsecutiveItemFailures = 3),
                    config =
                        LeaseDrainConfig(
                            interval = 3.seconds,
                            batchSize = 25,
                            leaseDuration = lease,
                            leaseBudgetFraction = 1.0,
                            maxAttempts = maxAttempts,
                            maxConsecutiveItemFailures = 3,
                        ),
                    metrics = NoDeliveryMetrics,
                )

            // Every round claims all five rows, then aborts on the three consecutive failures at the
            // head. The healthy tail is never reached, so it must not be charged for the rounds.
            repeat(maxAttempts) {
                shouldThrow<AlreadyLoggedWorkerFailure> { worker.runOnce() }
                expireAllLeases()
            }

            handledRefs.distinct() shouldBe poisonRefs
            fixture.database.transact {
                healthyRefs.forEach { reference ->
                    DeliveryTable
                        .selectAll()
                        .where { DeliveryTable.reference eq reference }
                        .single()[DeliveryTable.attempt] shouldBe 0
                }
            }

            // The poison gate now terminates only the three exhausted rows, and the healthy tail is
            // delivered on the next round instead of being failed silently.
            worker.runOnce()

            handledRefs.filter { it in healthyRefs } shouldBe healthyRefs
            fixture.database.transact {
                poisonRefs.forEach { reference ->
                    val row = DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.single()
                    row[DeliveryTable.state] shouldBe "FAILED"
                    row[DeliveryTable.attempt] shouldBe maxAttempts
                }
                healthyRefs.forEach { reference ->
                    val row = DeliveryTable.selectAll().where { DeliveryTable.reference eq reference }.single()
                    row[DeliveryTable.state] shouldBe "SENT"
                    row[DeliveryTable.attempt] shouldBe 1
                }
            }
        }
    })
