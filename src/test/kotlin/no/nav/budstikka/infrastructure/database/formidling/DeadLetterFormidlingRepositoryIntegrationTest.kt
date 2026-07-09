package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.PostgresTestFixture
import java.sql.DriverManager

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
                    """.trimIndent(),
                ).use { resultSet ->
                    check(resultSet.next())
                    val row =
                        DeadLetterRecord(
                            payload = resultSet.getString(DeadLetterFormidlingTable.payload.name),
                            topic = resultSet.getString(DeadLetterFormidlingTable.topic.name),
                            partition = resultSet.getInt(DeadLetterFormidlingTable.partition.name),
                            kafkaOffset = resultSet.getLong(DeadLetterFormidlingTable.kafkaOffset.name),
                            kafkaKey = resultSet.getString(DeadLetterFormidlingTable.kafkaKey.name),
                            failureReason = resultSet.getString(DeadLetterFormidlingTable.failureReason.name),
                            errorMessage = resultSet.getString(DeadLetterFormidlingTable.errorMessage.name),
                        )

                    check(!resultSet.next())
                    row
                }
        }
    }
