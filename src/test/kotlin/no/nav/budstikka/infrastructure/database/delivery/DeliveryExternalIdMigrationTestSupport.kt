package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.matchers.shouldBe
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID

internal fun PostgresTestFixture.migrateTo(target: String) {
    Flyway
        .configure()
        .dataSource(jdbcUrl, username, password)
        .locations("classpath:database.migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate()
}

internal data class LegacyCreate(
    val eventId: UUID = UUID.randomUUID(),
    val id: UUID = UUID.randomUUID(),
    val content: ArbeidsgivervarselCreate = syntheticCreate(),
    val reference: String = "synthetic-reference-$id",
)

internal fun syntheticCreate(meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED) =
    ArbeidsgivervarselCreate(
        orgnummer = TEST_ORGNUMMER,
        recipient = AltinnResource("synthetic-resource"),
        tag = "synthetic-tag",
        text = "synthetic text",
        link = "https://example.test/synthetic",
        meldingstype = meldingstype,
    )

internal fun PostgresTestFixture.insertLegacyCreate(create: LegacyCreate) {
    val payload = dispatchJson.encodeToString<DispatchContent>(create.content)
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO inbox_message (event_id, content, reference, state) VALUES (?, CAST(? AS jsonb), ?, 'PROCESSED')",
            ).use { statement ->
                statement.setObject(1, create.eventId)
                statement.setString(2, payload)
                statement.setString(3, create.reference)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                """
                INSERT INTO delivery (
                    id, inbox_event_id, reference, operation, channel, recipient_type, recipient_id, payload, state, attempt
                ) VALUES (?, ?, ?, 'CREATE', 'ARBEIDSGIVERVARSEL', 'VIRKSOMHET', ?, CAST(? AS jsonb), 'READY', 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, create.id)
                statement.setObject(2, create.eventId)
                statement.setString(3, create.reference)
                statement.setString(4, TEST_ORGNUMMER.value)
                statement.setString(5, payload)
                statement.executeUpdate()
            }
    }
}

internal fun PostgresTestFixture.externalId(deliveryId: UUID): String? =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("SELECT external_id FROM delivery WHERE id = ?").use { statement ->
            statement.setObject(1, deliveryId)
            statement.executeQuery().use { resultSet ->
                resultSet.next() shouldBe true
                resultSet.getString(1)
            }
        }
    }
