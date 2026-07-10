package no.nav.budstikka.infrastructure.database.dispatch

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp

@Suppress("unused")
object InboxMessageTable : Table("inbox_message") {
    val eventId = javaUUID("event_id").databaseGenerated()
    val payload = text("payload")
    val state = text("state").default("RECEIVED")
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
