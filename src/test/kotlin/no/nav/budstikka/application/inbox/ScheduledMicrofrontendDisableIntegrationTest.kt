package no.nav.budstikka.application.inbox

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import no.nav.budstikka.application.delivery.DeliveryWorker
import no.nav.budstikka.application.delivery.MicrofrontendChannelHandler
import no.nav.budstikka.application.port.ClaimedInboxMessage
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.application.port.MicrofrontendKey
import no.nav.budstikka.application.worker.LeaseBudgetDrainer
import no.nav.budstikka.application.worker.LeaseDrainConfig
import no.nav.budstikka.contract.Dispatch
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.Microfrontend
import no.nav.budstikka.contract.MicrofrontendDisable
import no.nav.budstikka.contract.MicrofrontendEnable
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.domain.decision.DecisionProcess
import no.nav.budstikka.domain.decision.Operation
import no.nav.budstikka.fakes.RecordingDeliveryMetrics
import no.nav.budstikka.fakes.TEST_SYKMELDT
import no.nav.budstikka.infrastructure.MutableClock
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.PostgresTransactionRunner
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.delivery.DeliveryTable
import no.nav.budstikka.infrastructure.database.delivery.PostgresDeliveryRepository
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.PostgresInboxMessageRepository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MICROFRONTEND_ID = "syfo-dialog"

/**
 * Pins ADR 0018 against PostgreSQL: an enable with `visibleUntil` schedules exactly one disable as a
 * derived inbox row, and the last command per (sykmeldt, microfrontendId) in ingest order wins.
 */
class ScheduledMicrofrontendDisableIntegrationTest :
    FunSpec({
        val fixture = PostgresTestFixture()
        val lease = 5.minutes
        val decisionProcess = DecisionProcess(emptyList())
        val earlier = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        val later = UUID.fromString("00000000-0000-0000-0000-0000000000c2")

        beforeSpec { fixture.migrate() }
        afterTest { fixture.reset() }
        afterSpec { fixture.close() }

        fun inbox() = PostgresInboxMessageRepository(fixture.database)

        fun effectuator(clock: Clock = Clock.System) =
            EffectuateDecision(
                transactionRunner = PostgresTransactionRunner(fixture.database),
                inboxMessageRepository = inbox(),
                deliveryRepository = PostgresDeliveryRepository(fixture.database),
                clock = clock,
            )

        // Whole seconds, so values survive the microsecond precision of timestamptz unchanged.
        fun inDays(days: Int): Instant = Instant.fromEpochSeconds(Clock.System.now().epochSeconds) + days.days

        suspend fun receive(
            eventId: UUID,
            content: DispatchContent,
        ) = inbox().saveBatch(listOf(InboxMessage(eventId, "ref-$eventId", content)))

        suspend fun claimAll(): Map<UUID, ClaimedInboxMessage> =
            inbox().claim(limit = 25, lease = lease, maxAttempts = 10).associateBy { it.message.eventId }

        suspend fun decide(
            claimed: ClaimedInboxMessage,
            effectuate: EffectuateDecision = effectuator(),
        ): EffectuationResult {
            val message = claimed.message
            return effectuate.effectuate(claimed.claim, decisionProcess.process(Dispatch(message.reference, message.content)))
        }

        suspend fun receiveAndDecide(
            eventId: UUID,
            content: DispatchContent,
        ): EffectuationResult {
            receive(eventId, content)
            return decide(claimAll().getValue(eventId))
        }

        suspend fun row(eventId: UUID): InboxRow? =
            fixture.database.transact {
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.eventId eq eventId }
                    .singleOrNull()
                    ?.let(::InboxRow)
            }

        suspend fun scheduledDisables(): List<InboxRow> =
            fixture.database.transact {
                InboxMessageTable
                    .selectAll()
                    .where { InboxMessageTable.sourceEventId.isNotNull() }
                    .map(::InboxRow)
            }

        suspend fun deliveries(inboxEventId: UUID): List<Pair<Operation, DispatchContent>> =
            fixture.database.transact {
                DeliveryTable
                    .selectAll()
                    .where { DeliveryTable.inboxEventId eq inboxEventId }
                    .map { Operation.valueOf(it[DeliveryTable.operation]) to it[DeliveryTable.payload] }
            }

        suspend fun update(
            eventId: UUID,
            receivedAt: Instant? = null,
            nextAttemptTime: Instant? = null,
        ) = fixture.database.transact {
            InboxMessageTable.update({ InboxMessageTable.eventId eq eventId }) {
                receivedAt?.let { value -> it[InboxMessageTable.receivedAt] = value }
                nextAttemptTime?.let { value -> it[InboxMessageTable.nextAttemptTime] = value }
            }
        }

        test("an enable with visibleUntil schedules one disable that waits until visibleUntil") {
            val visibleUntil = inDays(1)
            receiveAndDecide(earlier, enable(visibleUntil)) shouldBe EffectuationResult.Completed

            val source = checkNotNull(row(earlier))
            source.state shouldBe "PROCESSED"
            deliveries(earlier) shouldContainExactly listOf(Operation.CREATE to enable(visibleUntil))
            scheduledDisables() shouldContainExactly
                listOf(
                    InboxRow(
                        eventId = scheduledDisableEventId(earlier),
                        reference = source.reference,
                        content = disable(),
                        state = "WAIT",
                        nextAttemptTime = visibleUntil,
                        waitReason = SCHEDULED_DISABLE_WAIT_REASON,
                        receivedAt = source.receivedAt,
                        sourceEventId = earlier,
                    ),
                )
            claimAll().keys.shouldBeEmpty()
        }

        test("the scheduled disable wakes at visibleUntil and becomes an ordinary disable delivery") {
            receiveAndDecide(earlier, enable(inDays(1)))
            val derived = scheduledDisableEventId(earlier)
            update(derived, nextAttemptTime = Clock.System.now() - 1.seconds)

            val woken = claimAll().getValue(derived)
            woken.message shouldBe InboxMessage(derived, "ref-$earlier", disable())
            decide(woken) shouldBe EffectuationResult.Completed

            deliveries(derived) shouldContainExactly listOf(Operation.INACTIVATE to disable())
            val row = checkNotNull(row(derived))
            row.state shouldBe "PROCESSED"
            row.waitReason.shouldBeNull()
        }

        listOf(
            "an enable without visibleUntil" to enable(),
            "a disable" to disable(),
        ).forEach { (name, laterCommand) ->
            test("$name received later cancels the pending disable") {
                receiveAndDecide(earlier, enable(inDays(1)))
                receiveAndDecide(later, laterCommand) shouldBe EffectuationResult.Completed

                scheduledDisables().map { it.eventId to it.state } shouldContainExactly
                    listOf(scheduledDisableEventId(earlier) to "PROCESSED")
                deliveries(scheduledDisableEventId(earlier)).shouldBeEmpty()
            }
        }

        test("a later enable with a later visibleUntil extends visibility") {
            val extended = inDays(2)
            receiveAndDecide(earlier, enable(inDays(1)))
            receiveAndDecide(later, enable(extended))

            scheduledDisables().map { Triple(it.sourceEventId, it.state, it.nextAttemptTime) } shouldContainExactlyInAnyOrder
                listOf(
                    Triple(earlier, "PROCESSED", null),
                    Triple(later, "WAIT", extended),
                )
        }

        test("a later command cancels a scheduled disable that a worker has claimed but not decided") {
            receiveAndDecide(earlier, enable(inDays(1)))
            val derived = scheduledDisableEventId(earlier)
            update(derived, nextAttemptTime = Clock.System.now() - 1.seconds)
            val woken = claimAll().getValue(derived)

            receiveAndDecide(later, enable())
            decide(woken) shouldBe EffectuationResult.Skipped

            row(derived)?.state shouldBe "PROCESSED"
            deliveries(derived).shouldBeEmpty()
        }

        test("an earlier enable decided after a later command schedules nothing") {
            val visibleUntil = inDays(1)
            receive(earlier, enable(visibleUntil))
            receive(later, disable())
            val claims = claimAll()

            decide(claims.getValue(later)) shouldBe EffectuationResult.Completed
            decide(claims.getValue(earlier)) shouldBe EffectuationResult.Completed

            scheduledDisables().shouldBeEmpty()
            // The earlier enable itself is still delivered; ordering between deliveries is #315.
            deliveries(earlier) shouldContainExactly listOf(Operation.CREATE to enable(visibleUntil))
        }

        test("a later command that is not decided yet cancels the earlier schedule when it is decided") {
            receive(earlier, enable(inDays(1)))
            receive(later, enable())
            val claims = claimAll()

            decide(claims.getValue(earlier))
            row(scheduledDisableEventId(earlier))?.state shouldBe "WAIT"

            decide(claims.getValue(later))
            row(scheduledDisableEventId(earlier))?.state shouldBe "PROCESSED"
        }

        test("an earlier command does not cancel the disable a later command scheduled") {
            receive(earlier, enable())
            receive(later, enable(inDays(1)))
            val claims = claimAll()

            decide(claims.getValue(later))
            decide(claims.getValue(earlier))

            row(scheduledDisableEventId(later))?.state shouldBe "WAIT"
        }

        test("within one ingest batch the event id orders commands, also against a scheduled disable") {
            // One batch shares received_at; the source event id, not the derived one, orders the disable.
            inbox().saveBatch(
                listOf(
                    InboxMessage(later, "ref-later", enable(inDays(1))),
                    InboxMessage(earlier, "ref-earlier", enable()),
                ),
            )
            checkNotNull(row(earlier)).receivedAt shouldBe checkNotNull(row(later)).receivedAt
            val claims = claimAll()

            decide(claims.getValue(later))
            decide(claims.getValue(earlier))
            row(scheduledDisableEventId(later))?.state shouldBe "WAIT"

            receiveAndDecide(UUID.fromString("00000000-0000-0000-0000-0000000000c3"), disable())
            row(scheduledDisableEventId(later))?.state shouldBe "PROCESSED"
        }

        test("an enable whose visibleUntil has passed is delivered as an immediate disable") {
            receiveAndDecide(earlier, enable(inDays(1)))
            val now = inDays(0)
            receive(later, enable(visibleUntil = now))

            decide(claimAll().getValue(later), effectuator(MutableClock(now))) shouldBe EffectuationResult.Completed

            deliveries(later) shouldContainExactly listOf(Operation.INACTIVATE to disable())
            row(scheduledDisableEventId(later)).shouldBeNull()
            row(scheduledDisableEventId(earlier))?.state shouldBe "PROCESSED"
        }

        test("visibleUntil exactly 335 days after receipt is scheduled as given") {
            val receivedAt = inDays(0)
            val visibleUntil = receivedAt + SCHEDULED_DISABLE_HORIZON
            receive(earlier, enable(visibleUntil))
            update(earlier, receivedAt = receivedAt)

            decide(claimAll().getValue(earlier)) shouldBe EffectuationResult.Completed

            row(scheduledDisableEventId(earlier))?.nextAttemptTime shouldBe visibleUntil
        }

        test("visibleUntil beyond 335 days after receipt is scheduled at the horizon") {
            val receivedAt = inDays(0)
            receive(earlier, enable(receivedAt + SCHEDULED_DISABLE_HORIZON + 1.seconds))
            update(earlier, receivedAt = receivedAt)

            decide(claimAll().getValue(earlier)) shouldBe EffectuationResult.CompletedWithCappedScheduledDisable

            row(scheduledDisableEventId(earlier))?.nextAttemptTime shouldBe receivedAt + SCHEDULED_DISABLE_HORIZON
        }

        test("a reclaimed lease or a repeated decision schedules the disable once") {
            receive(earlier, enable(inDays(1)))
            val expired = claimAll().getValue(earlier)
            update(earlier, nextAttemptTime = Clock.System.now() - 1.minutes)
            val reclaimed = claimAll().getValue(earlier)

            decide(expired) shouldBe EffectuationResult.Skipped
            scheduledDisables().shouldBeEmpty()
            decide(reclaimed) shouldBe EffectuationResult.Completed
            decide(reclaimed) shouldBe EffectuationResult.Skipped

            scheduledDisables().map { it.eventId } shouldContainExactly listOf(scheduledDisableEventId(earlier))
            fixture.database.transact {
                inbox().saveDerivedInTransaction(
                    sourceEventId = earlier,
                    message = InboxMessage(scheduledDisableEventId(earlier), "ref-$earlier", disable()),
                    waitReason = SCHEDULED_DISABLE_WAIT_REASON,
                    notBefore = inDays(1),
                )
            } shouldBe false
        }

        test("decisions for the same key wait for each other on the key lock") {
            receive(earlier, enable(inDays(1)))
            receive(later, enable(inDays(2)))
            val claims = claimAll()
            val lockKey =
                PostgresInboxMessageRepository.microfrontendLockKey(MicrofrontendKey(TEST_SYKMELDT, MICROFRONTEND_ID))

            DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { holder ->
                holder.autoCommit = false
                holder.prepareStatement("SELECT pg_advisory_xact_lock(?)").use { statement ->
                    statement.setLong(1, lockKey)
                    statement.executeQuery().close()
                }
                coroutineScope {
                    val decisions =
                        listOf(later, earlier).map { eventId ->
                            async(Dispatchers.IO) { decide(claims.getValue(eventId)) }
                        }
                    delay(500.milliseconds)
                    decisions.none { it.isCompleted } shouldBe true

                    holder.commit()
                    decisions.awaitAll() shouldContainExactly
                        listOf(EffectuationResult.Completed, EffectuationResult.Completed)
                }
            }

            // Whichever decision ran first, only the later enable's disable is pending.
            scheduledDisables().filter { it.state == "WAIT" }.map { it.sourceEventId } shouldContainExactly listOf(later)
            (row(scheduledDisableEventId(earlier))?.state ?: "NOT_SCHEDULED") shouldBeIn
                listOf("NOT_SCHEDULED", "PROCESSED")
        }

        test("an enable with visibleUntil reaches the TMS fake as an enable and after visibleUntil as a disable") {
            val published = CopyOnWriteArrayList<Microfrontend>()
            val config =
                LeaseDrainConfig(
                    interval = 1.seconds,
                    batchSize = 25,
                    leaseDuration = lease,
                    leaseBudgetFraction = 0.8,
                    maxAttempts = 10,
                    maxConsecutiveItemFailures = 3,
                )
            val inboxWorker =
                InboxMessageWorker(
                    repository = inbox(),
                    effectuator = effectuator(),
                    decisionProcess = decisionProcess,
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config = config,
                    metrics = NoInboxMetrics,
                )
            val deliveryWorker =
                DeliveryWorker(
                    repository = PostgresDeliveryRepository(fixture.database),
                    handlers = mapOf(Channel.MICROFRONTEND to MicrofrontendChannelHandler { published += it }),
                    drainer = LeaseBudgetDrainer(leaseBudgetFraction = 0.8, maxConsecutiveItemFailures = 3),
                    config = config,
                    metrics = RecordingDeliveryMetrics(),
                )
            val visibleUntil = Clock.System.now() + 5.seconds
            receive(earlier, enable(visibleUntil))

            inboxWorker.runOnce()
            deliveryWorker.runOnce()
            published shouldContainExactly listOf(enable(visibleUntil))

            eventually(30.seconds) {
                inboxWorker.runOnce()
                deliveryWorker.runOnce()
                published shouldContainExactly listOf(enable(visibleUntil), disable())
            }
            Clock.System.now() shouldBeAtLeast visibleUntil
        }
    })

private infix fun Instant.shouldBeAtLeast(other: Instant) = (this >= other) shouldBe true

private fun enable(visibleUntil: Instant? = null) = MicrofrontendEnable(TEST_SYKMELDT, MICROFRONTEND_ID, visibleUntil)

private fun disable() = MicrofrontendDisable(TEST_SYKMELDT, MICROFRONTEND_ID)

private data class InboxRow(
    val eventId: UUID,
    val reference: String,
    val content: DispatchContent,
    val state: String,
    val nextAttemptTime: Instant?,
    val waitReason: String?,
    val receivedAt: Instant,
    val sourceEventId: UUID?,
) {
    constructor(row: org.jetbrains.exposed.v1.core.ResultRow) : this(
        eventId = row[InboxMessageTable.eventId],
        reference = row[InboxMessageTable.reference],
        content = row[InboxMessageTable.content],
        state = row[InboxMessageTable.state],
        nextAttemptTime = row[InboxMessageTable.nextAttemptTime],
        waitReason = row[InboxMessageTable.waitReason],
        receivedAt = row[InboxMessageTable.receivedAt],
        sourceEventId = row[InboxMessageTable.sourceEventId],
    )
}
