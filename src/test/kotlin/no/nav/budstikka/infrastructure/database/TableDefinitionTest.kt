package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.sql.DriverManager

class TableDefinitionTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterSpec { fixture.close() }

        fixture.tables.forEach { table ->
            test("Exposed mapping for '${table.tableName}' mirrors the migrated schema without drift") {
                val drift =
                    transaction(fixture.database) {
                        MigrationUtils.statementsRequiredForDatabaseMigration(table, withLogs = false)
                    }

                withClue("Schema drift for '${table.tableName}':\n${drift.joinToString("\n")}") {
                    drift.shouldBeEmpty()
                }
            }
        }

        test("every migrated table is registered in 'tables'") {
            transaction(fixture.database) {
                val tablesInSchema =
                    currentDialectMetadata.allTablesNames.filter { !it.contains("flyway") }
                val registeredTables = fixture.tables.map { "${fixture.schema}.${it.tableName}" }

                val missingFromList = tablesInSchema - registeredTables.toSet()
                val missingMigration = registeredTables - tablesInSchema.toSet()

                withClue(
                    "Migrated schema and the 'tables' list must match.\n" +
                        "Tables in schema but missing from 'tables': $missingFromList\n" +
                        "Tables in 'tables' but missing a migration: $missingMigration",
                ) {
                    tablesInSchema shouldContainExactlyInAnyOrder registeredTables
                }
            }
        }

        test("delivery retention index includes only terminal delivery states") {
            indexDefinition(
                fixture = fixture,
                indexName = "delivery_created_at_id_sent_failed_idx",
                failureMessage = "Delivery retention index was not found in schema '${fixture.schema}'",
            ).substringAfter(" WHERE ", missingDelimiterValue = "") shouldBe
                "(state = ANY (ARRAY['SENT'::text, 'FAILED'::text]))"
        }

        test("FERDIGSTILL indexes exist on inbox reference and delivery match columns") {
            indexDefinition(
                fixture = fixture,
                indexName = "inbox_message_reference_idx",
                failureMessage = "Inbox FERDIGSTILL index was not found in schema '${fixture.schema}'",
            ).contains("(reference)") shouldBe true

            indexDefinition(
                fixture = fixture,
                indexName = "delivery_ferdigstill_match_idx",
                failureMessage = "Delivery FERDIGSTILL index was not found in schema '${fixture.schema}'",
            ).contains("(reference, operation, channel, recipient_type, recipient_id, created_at, id)") shouldBe true
        }
    })

private fun indexDefinition(
    fixture: PostgresTestFixture,
    indexName: String,
    failureMessage: String,
): String =
    DriverManager
        .getConnection(fixture.jdbcUrl, fixture.username, fixture.password)
        .use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT indexdef
                    FROM pg_indexes
                    WHERE schemaname = ?
                      AND indexname = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, fixture.schema)
                    statement.setString(2, indexName)
                    statement.executeQuery().use { resultSet ->
                        check(resultSet.next()) { failureMessage }
                        resultSet.getString("indexdef")
                    }
                }
        }
