package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import org.flywaydb.core.Flyway
import java.sql.DriverManager
import java.util.UUID

class DeliveryExternalIdStorageMigrationIntegrationTest :
    FunSpec({
        test("V9 adds nullable external_id without backfilling historical deliveries") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("8")
                val historicalCreate = LegacyCreate()
                fixture.insertLegacyCreate(historicalCreate)

                fixture.deliveryColumnExists("external_id") shouldBe false

                fixture.migrateTo("9")

                fixture.deliveryColumnExists("external_id") shouldBe true
                fixture.deliveryColumnIsNullable("external_id") shouldBe true
                fixture.externalId(historicalCreate.id) shouldBe null
            }
        }

        test("an empty database bootstraps with nullable external_id") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate()

                fixture.deliveryColumnExists("external_id") shouldBe true
                fixture.deliveryColumnIsNullable("external_id") shouldBe true
            }
        }
    })

private data class LegacyCreate(
    val eventId: UUID = UUID.randomUUID(),
    val id: UUID = UUID.randomUUID(),
    val reference: String = "synthetic-reference-$id",
)

private fun PostgresTestFixture.migrateTo(target: String) {
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

private fun PostgresTestFixture.insertLegacyCreate(create: LegacyCreate) {
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                "INSERT INTO inbox_message (event_id, content, reference, state) VALUES (?, '{}', ?, 'PROCESSED')",
            ).use { statement ->
                statement.setObject(1, create.eventId)
                statement.setString(2, create.reference)
                statement.executeUpdate() shouldBe 1
            }
        connection
            .prepareStatement(
                """
                INSERT INTO delivery (
                    id, inbox_event_id, reference, operation, channel, recipient_type, recipient_id, payload, state, attempt
                ) VALUES (?, ?, ?, 'CREATE', 'ARBEIDSGIVERVARSEL', 'VIRKSOMHET', 'synthetic-orgnummer', '{}', 'READY', 0)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, create.id)
                statement.setObject(2, create.eventId)
                statement.setString(3, create.reference)
                statement.executeUpdate() shouldBe 1
            }
    }
}

private fun PostgresTestFixture.deliveryColumnExists(column: String): Boolean =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'delivery'
                      AND column_name = ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, column)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getBoolean(1)
                }
            }
    }

private fun PostgresTestFixture.deliveryColumnIsNullable(column: String): Boolean =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection
            .prepareStatement(
                """
                SELECT is_nullable
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name = 'delivery'
                  AND column_name = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, column)
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getString(1) == "YES"
                }
            }
    }

private fun PostgresTestFixture.externalId(deliveryId: UUID): String? =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("SELECT external_id FROM delivery WHERE id = ?").use { statement ->
            statement.setObject(1, deliveryId)
            statement.executeQuery().use { rows ->
                rows.next() shouldBe true
                rows.getString(1)
            }
        }
    }
