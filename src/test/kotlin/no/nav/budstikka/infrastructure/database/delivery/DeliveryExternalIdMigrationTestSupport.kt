package no.nav.budstikka.infrastructure.database.delivery

import io.kotest.matchers.shouldBe
import no.nav.budstikka.application.port.InboxMessage
import no.nav.budstikka.contract.AltinnResource
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.ArbeidsgivervarselCreate
import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Recipient
import no.nav.budstikka.fakes.TEST_ORGNUMMER
import no.nav.budstikka.infrastructure.database.PostgresTestFixture
import no.nav.budstikka.infrastructure.database.dispatch.InboxMessageRepositoryImpl
import org.flywaydb.core.Flyway
import java.sql.Connection
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

internal fun PostgresTestFixture.deleteInbox(eventId: UUID) {
    dataSource.connection.use { connection ->
        connection.prepareStatement("DELETE FROM inbox_message WHERE event_id = ?").use { statement ->
            statement.setObject(1, eventId)
            statement.executeUpdate() shouldBe 1
        }
    }
}

internal fun PostgresTestFixture.insertDependent(
    create: LegacyCreate,
    externalId: String?,
    state: String = "READY",
    attempt: Int = 0,
): UUID {
    val id = UUID.randomUUID()
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO delivery (
                    id, reference, operation, channel, recipient_type, recipient_id, payload,
                    create_external_id, source_create_delivery_id, state, attempt
                ) VALUES (?, ?, 'INACTIVATE', 'ARBEIDSGIVERVARSEL', 'VIRKSOMHET', ?, CAST(? AS jsonb), ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setString(2, create.reference)
                statement.setString(3, TEST_ORGNUMMER.value)
                statement.setString(4, dispatchJson.encodeToString<DispatchContent>(create.content))
                statement.setString(5, externalId)
                statement.setObject(6, create.id)
                statement.setString(7, state)
                statement.setInt(8, attempt)
                statement.executeUpdate() shouldBe 1
            }
    }
    return id
}

internal suspend fun PostgresTestFixture.insertTriggerProbe(
    message: InboxMessage,
    draft: DeliveryDraft,
    id: UUID = UUID.randomUUID(),
): UUID {
    InboxMessageRepositoryImpl(database).saveBatch(listOf(message))
    val (recipientType, recipientId) =
        when (val recipient = draft.recipient) {
            is Recipient.Person -> "PERSON" to recipient.ident.value
            is Recipient.Virksomhet -> "VIRKSOMHET" to recipient.orgnummer.value
        }
    dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                """
                INSERT INTO delivery (
                    id, inbox_event_id, reference, operation, channel, recipient_type, recipient_id, payload, create_external_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, message.eventId)
                statement.setString(3, draft.reference)
                statement.setString(4, draft.operation.name)
                statement.setString(5, draft.channel.name)
                statement.setString(6, recipientType)
                statement.setString(7, recipientId)
                statement.setString(8, dispatchJson.encodeToString<DispatchContent>(draft.content))
                statement.setString(9, draft.createExternalId)
                statement.executeUpdate() shouldBe 1
            }
    }
    return id
}

internal data class DeliverySnapshot(
    val id: UUID,
    val inboxEventId: UUID?,
    val externalId: String?,
    val sourceId: UUID?,
    val state: String,
    val attempt: Int,
    val error: String?,
)

internal fun PostgresTestFixture.snapshot(): Map<UUID, DeliverySnapshot> = dataSource.connection.use { it.snapshot() }

internal fun Connection.snapshot(): Map<UUID, DeliverySnapshot> =
    prepareStatement(
        "SELECT id, inbox_event_id, create_external_id, source_create_delivery_id, state, attempt, error_message FROM delivery",
    ).use { statement ->
        statement.executeQuery().use { rows ->
            buildMap {
                while (rows.next()) {
                    val id = rows.getObject("id", UUID::class.java)
                    put(
                        id,
                        DeliverySnapshot(
                            id,
                            rows.getObject("inbox_event_id", UUID::class.java),
                            rows.getString("create_external_id"),
                            rows.getObject("source_create_delivery_id", UUID::class.java),
                            rows.getString("state"),
                            rows.getInt("attempt"),
                            rows.getString("error_message"),
                        ),
                    )
                }
            }
        }
    }

internal fun PostgresTestFixture.createExternalId(deliveryId: UUID): String? =
    DriverManager.getConnection(jdbcUrl, username, password).use { connection ->
        connection.prepareStatement("SELECT create_external_id FROM delivery WHERE id = ?").use { statement ->
            statement.setObject(1, deliveryId)
            statement.executeQuery().use { resultSet ->
                resultSet.next() shouldBe true
                resultSet.getString(1)
            }
        }
    }
