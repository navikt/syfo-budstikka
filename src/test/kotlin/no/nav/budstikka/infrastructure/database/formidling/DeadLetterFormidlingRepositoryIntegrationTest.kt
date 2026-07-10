package no.nav.budstikka.infrastructure.database.formidling

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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

        test("save persisterer en rad i dead_letter_formidling") {
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

            val antall = transaction(fixture.database) { DeadLetterFormidlingTable.selectAll().count() }
            antall shouldBe 1L
        }
    })
