package no.nav.budstikka.infrastructure.database.formidling

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp

object DeadLetterFormidlingTable : Table("dead_letter_formidling") {
    val id = javaUUID("id")
    val payload = text("payload")
    val topic = text("topic")
    val partition = integer("partition")
    val kafkaOffset = long("kafka_offset")
    val kafkaKey = text("kafka_key").nullable()
    val failureReason = text("failure_reason")
    val errorMessage = text("error_message").nullable()
    val receivedAt = timestamp("received_at")

    override val primaryKey = PrimaryKey(id)
}
