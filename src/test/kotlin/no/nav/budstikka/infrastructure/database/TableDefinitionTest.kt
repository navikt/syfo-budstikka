package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import no.nav.budstikka.infrastructure.database.formidling.DeadLetterFormidlingTable
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingTable
import no.nav.budstikka.infrastructure.database.leveranse.LeveranseTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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

        val tables: List<Table> = listOf(
            InboxFormidlingTable,
            DeadLetterFormidlingTable,
            LeveranseTable
        )

        tables.forEach { table ->
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
    })
