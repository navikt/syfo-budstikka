package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.retention.RetentionPolicy
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.domain.decision.Channel
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.retention.PostgresRetentionRepository
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

class NotificationIdentityMigrationTest :
    FunSpec({
        test("migration and retention preserve known legacy identity including writes from old replicas") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate("12")
                val original = UUID.fromString("00000000-0000-0000-0000-000000000811")
                val originalInbox = UUID.fromString("00000000-0000-0000-0000-000000000821")
                val lateInbox = UUID.fromString("00000000-0000-0000-0000-000000000822")
                val lateWriter = UUID.fromString("00000000-0000-0000-0000-000000000812")
                val neverAttempted = UUID.fromString("00000000-0000-0000-0000-000000000813")
                val unknownTarget = UUID.fromString("00000000-0000-0000-0000-000000000814")
                DriverManager.getConnection(fixture.jdbcUrl, fixture.username, fixture.password).use { connection ->
                    connection.seedLegacyNotification(original, originalInbox)
                    connection.seedLegacyNotification(neverAttempted, null)
                    connection.seedLegacyNotification(unknownTarget, null, attempt = 1)
                    fixture.migrate()
                    connection.seedLegacyNotification(lateWriter, lateInbox)
                }

                PostgresRetentionRepository(fixture.database, RetentionPolicy()).run(100)
                val claims =
                    PostgresDeliveryRepository(fixture.database)
                        .claim(10, 5.minutes, 10, setOf(Channel.ARBEIDSGIVERVARSEL))
                        .associateBy { it.id }
                claims.getValue(original).inboxEventId shouldBe null
                claims.getValue(original).notificationId shouldBe originalInbox.toString()
                claims.getValue(lateWriter).inboxEventId shouldBe null
                claims.getValue(lateWriter).notificationId shouldBe lateInbox.toString()
                claims.getValue(neverAttempted).notificationId shouldBe neverAttempted.toString()
                claims.getValue(unknownTarget).notificationId shouldBe null
            }
        }
    })

/** Reproduces an old writer: it knows neither notification_id nor the new identity marker. */
private fun Connection.seedLegacyNotification(
    id: UUID,
    source: UUID?,
    attempt: Int = 0,
) {
    val content: DispatchContent =
        ArbeidsgivervarselCreate(
            TEST_ORGNUMMER,
            AltinnResource("synthetic-resource"),
            "synthetic-tag",
            "Synthetic task",
            "https://nav.no/test",
        )
    val payload = dispatchJson.encodeToString(content)
    if (source != null) {
        prepareStatement(
            "INSERT INTO inbox_message(event_id, reference, content, state, received_at) VALUES (?, ?, ?::jsonb, 'PROCESSED', now() - interval '101 days')",
        ).use {
            it.setObject(1, source)
            it.setString(2, id.toString())
            it.setString(3, payload)
            it.executeUpdate()
        }
    }
    prepareStatement(
        """
        INSERT INTO delivery(id, inbox_event_id, reference, operation, channel, recipient_type, recipient_id, payload, attempt)
        VALUES (?, ?, ?, 'CREATE', 'ARBEIDSGIVERVARSEL', 'VIRKSOMHET', ?, ?::jsonb, ?)
        """.trimIndent(),
    ).use {
        it.setObject(1, id)
        it.setObject(2, source)
        it.setString(3, id.toString())
        it.setString(4, TEST_ORGNUMMER.value)
        it.setString(5, payload)
        it.setInt(6, attempt)
        it.executeUpdate()
    }
}
