package no.nav.budstikka.infrastructure.replay

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.fakes.inboxMessage
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterMessageTable
import no.nav.budstikka.infrastructure.database.dispatch.DeadLetterRecord
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID

class DeadLetterReplayerIntegrationTest :
    FunSpec({
        testExecutionMode = TestExecutionMode.Sequential
        val fixture = PostgresTestFixture()

        beforeSpec {
            fixture.migrate()
        }

        afterTest {
            fixture.reset()
        }

        afterSpec {
            fixture.close()
        }

        test("replays parseable rows into inbox and retains unparseable rows") {
            val deadLetters = DeadLetterMessageRepositoryImpl(fixture.database)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.randomUUID()
            deadLetters.saveBatch(
                listOf(
                    deadLetter(eventId, VALID_PAYLOAD_WITH_NULL_SENDING_WINDOW),
                    deadLetter(UUID.randomUUID(), """{"personIdentifier":"31129956715" """),
                ),
            )

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 1, skipped = 1)

            fixture.database.transact {
                val inboxRow = InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.single()
                inboxRow[InboxMessageTable.state] shouldBe InboxMessageState.RECEIVED.name
                DeadLetterMessageTable.selectAll().count() shouldBe 1
            }
        }

        test("replaying a dead letter whose inbox row was already committed does not duplicate the inbox row") {
            val deadLetters = DeadLetterMessageRepositoryImpl(fixture.database)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val eventId = UUID.randomUUID()
            inbox.saveBatch(listOf(inboxMessage(eventId)))
            deadLetters.saveBatch(listOf(deadLetter(eventId, VALID_PAYLOAD_WITH_NULL_SENDING_WINDOW)))

            DeadLetterReplayer(deadLetters, inbox).replay(limit = 100) shouldBe ReplayResult(replayed = 1, skipped = 0)

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq eventId }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().count() shouldBe 0
            }
        }

        test("replays a highest-ID row after same-timestamp unparseable rows") {
            val deadLetters = DeadLetterMessageRepositoryImpl(fixture.database)
            val inbox = InboxMessageRepositoryImpl(fixture.database)
            val replayedEventId = UUID.fromString("00000000-0000-0000-0000-000000000004")
            val unparseableEventIds =
                listOf(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"),
                    UUID.fromString("00000000-0000-0000-0000-000000000002"),
                    UUID.fromString("00000000-0000-0000-0000-000000000003"),
                )
            deadLetters.saveBatch(
                listOf(
                    deadLetter(replayedEventId, VALID_PAYLOAD_WITH_NULL_SENDING_WINDOW),
                    *unparseableEventIds.map { deadLetter(it, """{"personIdentifier":"12345678901" """) }.toTypedArray(),
                ),
            )
            fixture.database.transact {
                (listOf(replayedEventId) + unparseableEventIds).forEach { eventId ->
                    DeadLetterMessageTable.update({ DeadLetterMessageTable.eventId eq eventId }) {
                        it[id] = eventId
                    }
                }
            }

            deadLetters.findReplayable(limit = 2, offset = 0).map { it.eventId } shouldContainExactly unparseableEventIds.take(2)

            val replayResult = DeadLetterReplayer(deadLetters, inbox).replay(limit = 2)
            replayResult.replayed shouldBe 1

            fixture.database.transact {
                InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq replayedEventId }.count() shouldBe 1
                DeadLetterMessageTable.selectAll().count() shouldBe 3
            }
        }
    })

private fun deadLetter(
    eventId: UUID,
    payload: String,
): DeadLetterRecord =
    DeadLetterRecord(
        payload = payload,
        topic = "topic",
        partition = 0,
        kafkaOffset = 0,
        kafkaKey = null,
        eventId = eventId,
        failureReason = "UNPARSEABLE_PAYLOAD",
        errorMessage = null,
    )

private const val VALID_PAYLOAD_WITH_NULL_SENDING_WINDOW =
    """
    {
      "reference": "ref-1",
      "content": {
        "type": "BrukervarselCreate",
        "personIdentifier": "12345678901",
        "varseltype": "BESKJED",
        "text": "Hei",
        "sendingWindow": null
      }
    }
    """
