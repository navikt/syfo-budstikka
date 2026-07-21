package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.dispatchJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

@Suppress("unused")
object InboxMessageTable : Table("inbox_message") {
    val eventId = javaUUID("event_id").databaseGenerated()

    // Hydrert ved ingest (ADR 0008): `content` er sealed `DispatchContent` som jsonb (speiler
    // DeliveryTable.payload), `reference` løftet ut som egen kolonne. Rå payload droppet.
    val content = jsonb<DispatchContent>("content", dispatchJson)
    val reference = text("reference")
    val state = text("state").default(InboxMessageState.RECEIVED.name)
    val dropReason = text("drop_reason").nullable()
    val attempt = integer("attempt").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val receivedAt = timestamp("received_at").defaultExpression(CurrentTimestamp)
    val processedAt = timestamp("processed_at").nullable()
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(eventId)

    init {
        index("inbox_message_state_next_attempt_time_idx", false, state, nextAttemptTime)
    }
}
