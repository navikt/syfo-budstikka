package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.infrastructure.database.config.timestamptz
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp

object DeadLetterMessageTable : Table("dead_letter_message") {
    val id = javaUUID("id").databaseGenerated()
    val payload = text("payload")

    // Best-effort correlation: null when the header was missing or invalid.
    val eventId = javaUUID("event_id").nullable()
    val topic = text("topic")
    val partition = integer("partition")
    val kafkaOffset = long("kafka_offset")
    val kafkaKey = text("kafka_key").nullable()
    val failureReason = text("failure_reason")
    val errorMessage = text("error_message").nullable()
    val receivedAt = timestamptz("received_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        index("dead_letter_message_received_at_id_idx", false, receivedAt, id)
    }
}
