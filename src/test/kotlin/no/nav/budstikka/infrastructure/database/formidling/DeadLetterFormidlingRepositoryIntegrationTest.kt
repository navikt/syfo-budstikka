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

            with(readDeadLetterRow(fixture)) {
                payload shouldBe record.payload
                topic shouldBe record.topic
                partition shouldBe record.partition
                kafkaOffset shouldBe record.kafkaOffset
                kafkaKey shouldBe record.kafkaKey
                failureReason shouldBe record.failureReason
                errorMessage shouldBe record.errorMessage
            }
        }
    })

private fun readDeadLetterRow(fixture: PostgresTestFixture): DeadLetterRecord =
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

                    DeadLetterRecord(
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
