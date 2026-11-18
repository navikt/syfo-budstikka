package no.nav.budstikka.infrastructure.database.config

import io.kotest.core.annotation.Isolate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.fakes.microfrontendEnable
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageState
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.DriverManager
import java.util.TimeZone
import java.util.UUID
import kotlin.time.Instant

// Changes the JVM's default time zone, so no other spec may run at the same time.
@Isolate
class TimestamptzTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterSpec { fixture.close() }

        test("both instants at 02:30 when Oslo leaves summer time keep their UTC time") {
            val instants = listOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T01:30:00Z"))
            val ids = instants.map { UUID.randomUUID() }

            val readBack =
                withDefaultTimeZone("Europe/Oslo") {
                    fixture.database.transact {
                        ids.zip(instants).forEach { (id, instant) ->
                            InboxMessageTable.insert {
                                it[eventId] = id
                                it[content] = microfrontendEnable()
                                it[reference] = "ref-$id"
                                it[state] = InboxMessageState.WAIT.name
                                it[waitReason] = "SENDING_WINDOW"
                                it[nextAttemptTime] = instant
                                it[receivedAt] = instant
                            }
                        }
                        ids.map { id ->
                            InboxMessageTable.selectAll().where { InboxMessageTable.eventId eq id }.single().let {
                                it[InboxMessageTable.receivedAt] to it[InboxMessageTable.nextAttemptTime]
                            }
                        }
                    }
                }

            ids.map { storedEpochs(fixture, it) } shouldBe instants.map { it to it }
            readBack shouldBe instants.map { it to it }
        }
    })

/** `received_at` and `next_attempt_time` as PostgreSQL stores them, read without Exposed. */
private fun storedEpochs(
    fixture: PostgresTestFixture,
    eventId: UUID,
): Pair<Instant, Instant> =
    DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
        connection
            .prepareStatement(
                "SELECT extract(epoch FROM received_at)::bigint, extract(epoch FROM next_attempt_time)::bigint " +
                    "FROM inbox_message WHERE event_id = ?",
            ).use { statement ->
                statement.setObject(1, eventId)
                statement.executeQuery().use { result ->
                    check(result.next()) { "Row $eventId was not stored" }
                    Instant.fromEpochSeconds(result.getLong(1)) to Instant.fromEpochSeconds(result.getLong(2))
                }
            }
    }

private inline fun <T> withDefaultTimeZone(
    id: String,
    block: () -> T,
): T {
    val original = TimeZone.getDefault()
    TimeZone.setDefault(TimeZone.getTimeZone(id))
    try {
        return block()
    } finally {
        TimeZone.setDefault(original)
    }
}
