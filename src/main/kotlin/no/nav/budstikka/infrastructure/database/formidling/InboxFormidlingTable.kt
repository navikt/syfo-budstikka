package no.nav.budstikka.infrastructure.database.formidling

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp

object InboxFormidlingTable : Table("inbox_hendelse") {
    val eventId = javaUUID("event_id")
    val payload = text("payload")
    val status = text("status").default("MOTTATT")
    val dropAarsak = text("drop_aarsak").nullable()
    val forsok = integer("forsok").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val receivedAt = timestamp("received_at")
    val processedAt = timestamp("processed_at").nullable()
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(eventId)
}
