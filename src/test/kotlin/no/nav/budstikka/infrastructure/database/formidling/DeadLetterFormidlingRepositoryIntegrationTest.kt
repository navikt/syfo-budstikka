package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.PostgresTestFixture
import java.sql.DriverManager
import java.util.UUID

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

        test("save skriver rad i dead_letter_formidling") {
            val repository = DeadLetterFormidlingRepositoryImpl(fixture.database)
            val record =
                DeadLetterRecord(
                    payload = "{}",
                    topic = "team-esyfo.formidling.v1",
                    partition = 0,
                    kafkaOffset = 42L,
                    kafkaKey = "key",
                    failureReason = "MISSING_EVENT_ID",
                    errorMessage = "mangler header",
                )

            repository.save(record)

            val row = readDeadLetterRow(fixture)

            row.payload shouldBe record.payload
            row.topic shouldBe record.topic
            row.partition shouldBe record.partition
            row.kafkaOffset shouldBe record.kafkaOffset
            row.kafkaKey shouldBe record.kafkaKey
            row.failureReason shouldBe record.failureReason
            row.errorMessage shouldBe record.errorMessage
        }
    })

private data class DeadLetterRow(
    val id: UUID,
    val payload: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
    val kafkaKey: String?,
    val failureReason: String,
    val errorMessage: String?,
)

private fun readDeadLetterRow(fixture: PostgresTestFixture): DeadLetterRow =
    DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    """
                    SELECT id, payload, topic, partition, kafka_offset, kafka_key, failure_reason, error_message
                    FROM dead_letter_formidling
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())

                    DeadLetterRow(
                        id = resultSet.getObject("id", UUID::class.java),
                        payload = resultSet.getString("payload"),
                        topic = resultSet.getString("topic"),
                        partition = resultSet.getInt("partition"),
                        kafkaOffset = resultSet.getLong("kafka_offset"),
                        kafkaKey = resultSet.getString("kafka_key"),
                        failureReason = resultSet.getString("failure_reason"),
                        errorMessage = resultSet.getString("error_message"),
                    )
                }
        }
    }
