package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import no.nav.budstikka.infrastructure.database.PostgresTestFixture

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

                fixture.migrate()

                fixture.externalId(historicalCreate.id) shouldBe null
            }
        }

        test("V10 adds the source CREATE dependency column and index after V9") {
            PostgresTestFixture().use { fixture ->
                fixture.migrateTo("9")
                fixture.deliveryColumnExists("source_create_delivery_id") shouldBe false

                fixture.migrate()

                fixture.deliveryColumnExists("source_create_delivery_id") shouldBe true
                fixture.deliverySelfReferenceExists("source_create_delivery_id") shouldBe true
                fixture.deliveryIndexExists("delivery_source_create_delivery_id_idx") shouldBe true
            }
        }

        test("an empty database bootstraps with external identity and source dependency schema") {
            PostgresTestFixture().use { fixture ->
                fixture.migrate()

                fixture.deliveryColumnExists("external_id") shouldBe true
                fixture.deliveryColumnIsNullable("external_id") shouldBe true
                fixture.deliveryColumnExists("source_create_delivery_id") shouldBe true
                fixture.deliverySelfReferenceExists("source_create_delivery_id") shouldBe true
                fixture.deliveryIndexExists("delivery_source_create_delivery_id_idx") shouldBe true
            }
        }
    })

private fun PostgresTestFixture.deliveryColumnExists(column: String): Boolean =
    dataSource.connection.use { connection ->
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
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }

private fun PostgresTestFixture.deliveryColumnIsNullable(column: String): Boolean =
    dataSource.connection.use { connection ->
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

private fun PostgresTestFixture.deliveryIndexExists(index: String): Boolean =
    dataSource.connection.use { connection ->
        connection
            .prepareStatement("SELECT to_regclass(?::text) IS NOT NULL")
            .use { statement ->
                statement.setString(1, "$schema.$index")
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }

private fun PostgresTestFixture.deliverySelfReferenceExists(column: String): Boolean =
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_constraint
                    JOIN pg_attribute
                      ON pg_attribute.attrelid = conrelid
                     AND pg_attribute.attnum = ANY (conkey)
                    WHERE contype = 'f'
                      AND conrelid = ?::regclass
                      AND confrelid = ?::regclass
                      AND pg_attribute.attname = ?
                )
                """.trimIndent(),
            ).use { statement ->
                val delivery = "$schema.delivery"
                statement.setString(1, delivery)
                statement.setString(2, delivery)
                statement.setString(3, column)
                statement.executeQuery().use { rows ->
                    rows.next() shouldBe true
                    rows.getBoolean(1)
                }
            }
    }
