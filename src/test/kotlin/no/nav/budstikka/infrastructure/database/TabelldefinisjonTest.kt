package no.nav.budstikka.infrastructure.database

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import no.nav.budstikka.infrastructure.database.formidling.DeadLetterFormidlingTable
import no.nav.budstikka.infrastructure.database.formidling.InboxFormidlingTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/**
 * Validerer at hver Exposed-tabellmapping speiler det migrerte Postgres-skjemaet eksakt.
 *
 * [MigrationUtils.statementsRequiredForDatabaseMigration] gir de DDL-setningene Exposed måtte
 * kjørt for å gjøre databasen lik mappingen. Tom liste = mapping og migrering er i sync; enhver
 * setning avslører drift (manglende kolonne-default, uregistrert indeks, feil nullability osv.).
 * Erstatter den tidligere manuelle kolonne-for-kolonne-verifiseringen i repository-testene.
 */
class TabelldefinisjonTest :
    FunSpec({
        val fixture = PostgresTestFixture()

        beforeSpec { fixture.migrate() }
        afterSpec { fixture.close() }

        val tabeller: List<Table> = listOf(InboxFormidlingTable, DeadLetterFormidlingTable)

        tabeller.forEach { tabell ->
            test("Exposed-mappingen for '${tabell.tableName}' speiler migrert skjema uten drift") {
                val drift =
                    transaction(fixture.database) {
                        MigrationUtils.statementsRequiredForDatabaseMigration(tabell, withLogs = false)
                    }

                withClue("Skjema-drift for '${tabell.tableName}':\n${drift.joinToString("\n")}") {
                    drift.shouldBeEmpty()
                }
            }
        }
    })
