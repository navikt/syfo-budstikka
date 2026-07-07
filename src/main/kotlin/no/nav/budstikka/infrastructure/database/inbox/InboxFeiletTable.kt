package no.nav.budstikka.infrastructure.database.inbox

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp

object InboxFeiletTable : Table("inbox_feilet") {
    val id = long("id").autoIncrement()
    val payload = text("payload")
    val topic = text("topic")
    val partisjon = integer("partisjon")
    val kafkaOffset = long("kafka_offset")
    val kafkaKey = text("kafka_key").nullable()
    val feilaarsak = text("feilaarsak")
    val feilmelding = text("feilmelding").nullable()
    val mottattTid = timestamp("mottatt_tid")

    override val primaryKey = PrimaryKey(id)
}
