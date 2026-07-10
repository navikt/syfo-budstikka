package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/**
 * Verifies that each Exposed table mapping mirrors the migrated Postgres schema exactly.
 *
 * [MigrationUtils.statementsRequiredForDatabaseMigration] returns the DDL statements Exposed would
 * have to run to make the database match the mapping. An empty list means the mapping and the
 * migration are in sync; any statement reveals drift (missing column default, unregistered index,
 * wrong nullability, etc.). Replaces the previous manual column-by-column verification in the
 * repository tests.
 */
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
                val registeredTables = fixture.tables.map { "public.${it.tableName}" }

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
    })
