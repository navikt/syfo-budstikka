package no.nav.budstikka.infrastructure.database.dispatch

import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.concurrency.TestExecutionMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

class DeadLetterMessageRepositoryIntegrationTest :
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

        test("findReplayable filters by failure reason and event ID") {
            val repository = DeadLetterMessageRepositoryImpl(fixture.database)
            val replayableEventId = UUID.randomUUID()
            repository.saveBatch(
                listOf(
                    deadLetter(eventId = replayableEventId, failureReason = "UNPARSEABLE_PAYLOAD"),
                    deadLetter(eventId = UUID.randomUUID(), failureReason = "MISSING_PAYLOAD"),
                    deadLetter(eventId = null, failureReason = "UNPARSEABLE_PAYLOAD"),
                ),
            )

            repository.findReplayable(limit = 100, offset = 0).map { it.eventId } shouldContainExactly listOf(replayableEventId)
        }

        test("deleteByIds deletes only the selected dead-letter rows") {
            val repository = DeadLetterMessageRepositoryImpl(fixture.database)
            repository.saveBatch(
                listOf(
                    deadLetter(eventId = UUID.randomUUID()),
                    deadLetter(eventId = UUID.randomUUID()),
                ),
            )
            val replayable = repository.findReplayable(limit = 100, offset = 0)

            repository.deleteByIds(listOf(replayable.first().id))

            repository.findReplayable(limit = 100, offset = 0).map { it.id } shouldContainExactly listOf(replayable.last().id)
            fixture.database.transact { DeadLetterMessageTable.selectAll().count() } shouldBe 1
        }
    })

private fun deadLetter(
    eventId: UUID?,
    failureReason: String = "UNPARSEABLE_PAYLOAD",
): DeadLetterRecord =
    DeadLetterRecord(
        payload = """{"reference":"ref","content":{"type":"BrukervarselCreate"}}""",
        topic = "topic",
        partition = 0,
        kafkaOffset = 0,
        kafkaKey = null,
        eventId = eventId,
        failureReason = failureReason,
        errorMessage = null,
    )
