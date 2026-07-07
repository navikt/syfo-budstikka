package no.nav.budstikka.infrastructure.database.inbox

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.timestamp

object InboxHendelseTable : Table("inbox_hendelse") {
    val eventId = javaUUID("event_id")
    val referanse = text("referanse")
    val payload = text("payload")
    val status = text("status").default("MOTTATT")
    val dropAarsak = text("drop_aarsak").nullable()
    val forsok = integer("forsok").default(0)
    val nesteForsokTid = timestamp("neste_forsok_tid").nullable()
    val mottattTid = timestamp("mottatt_tid")
    val behandletTid = timestamp("behandlet_tid").nullable()
    val feilmelding = text("feilmelding").nullable()

    override val primaryKey = PrimaryKey(eventId)
}
