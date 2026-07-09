package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.assertRow
import java.sql.ResultSet

class DeadLetterFormidlingRepositoryIntegrationTest :
    FunSpec({
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

        test("saves a row in dead_letter_formidling") {
            val repository = DeadLetterFormidlingRepositoryImpl(fixture.database)
            val record =
                DeadLetterRecord(
                    payload = "{}",
                    topic = "team-esyfo.formidling.v1",
                    partition = 0,
                    kafkaOffset = 42L,
                    kafkaKey = "key",
                    failureReason = "MISSING_EVENT_ID",
                    errorMessage = "missing header",
                )

            repository.save(record)

            assertRow(
                fixture = fixture,
                query =
                selectQuery,
            ) {
                shouldContainDeadLetterFormidling(record)
            }
        }
    })

private fun ResultSet.shouldContainDeadLetterFormidling(record: DeadLetterRecord) {
    getString(DeadLetterFormidlingTable.payload.name) shouldBe record.payload
    getString(DeadLetterFormidlingTable.topic.name) shouldBe record.topic
    getInt(DeadLetterFormidlingTable.partition.name) shouldBe record.partition
    getLong(DeadLetterFormidlingTable.kafkaOffset.name) shouldBe record.kafkaOffset
    getString(DeadLetterFormidlingTable.kafkaKey.name) shouldBe record.kafkaKey
    getString(DeadLetterFormidlingTable.failureReason.name) shouldBe record.failureReason
    getString(DeadLetterFormidlingTable.errorMessage.name) shouldBe record.errorMessage
    check(getObject(DeadLetterFormidlingTable.receivedAt.name) != null)
}

private val selectQuery =
    """
    SELECT
        ${DeadLetterFormidlingTable.id.name},
        ${DeadLetterFormidlingTable.payload.name},
        ${DeadLetterFormidlingTable.topic.name},
        ${DeadLetterFormidlingTable.partition.name},
        ${DeadLetterFormidlingTable.kafkaOffset.name},
        ${DeadLetterFormidlingTable.kafkaKey.name},
        ${DeadLetterFormidlingTable.failureReason.name},
        ${DeadLetterFormidlingTable.errorMessage.name},
        ${DeadLetterFormidlingTable.receivedAt.name}
    FROM dead_letter_formidling
    """.trimIndent()
