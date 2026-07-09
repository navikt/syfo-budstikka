package no.nav.budstikka.infrastructure.database

import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.config.transact
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.Query

/**
 * Assertion-scope som lar en lese rad-verdier via Exposed-kolonnene i stedet for rå JDBC-strenger.
 * `row[column]` gir riktig Kotlin-type (jsonb → Formidlingsinnhold, uuid → UUID, timestamp → Instant),
 * så testen slipper manuell type-mapping.
 */
class RowAssert(
    val row: ResultRow,
) {
    infix fun <T> Column<T>.shouldBe(expected: T) {
        row[this] shouldBe expected
    }
}

/** Kjører [query], krever nøyaktig én rad, og asserter på den via [RowAssert]. */
suspend fun PostgresTestFixture.singleRow(
    query: Query,
    assertion: RowAssert.() -> Unit,
) {
    database.transact {
        val rows = query.toList()
        check(rows.size == 1) { "forventet nøyaktig én rad, fikk ${rows.size}" }
        RowAssert(rows.single()).assertion()
    }
}

/** Antall rader [query] gir. */
suspend fun PostgresTestFixture.rowCount(query: Query): Long = database.transact { query.count() }
