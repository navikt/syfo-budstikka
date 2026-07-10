package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.dispatchJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Speiler `V3__delivery.sql` eksakt (jf. #50-lærdommen: skjema↔mapping må stemme, ellers
 * feiler det først i runtime mot ekte Postgres). Domenekolonner beholder domenespråket
 * (`reference`/`operation`/`channel`/`recipient_*`); infra-kolonner følger søster-tabellen
 * `inbox_delivery` (`state`/`attempt`/`next_attempt_time`/`created_at`).
 */
@Suppress("unused")
object DeliveryTable : Table("delivery") {
    val id = javaUUID("id").databaseGenerated()
    val inboxEventId = javaUUID("inbox_event_id").nullable()
    val reference = text("reference")
    val operation = text("operation")
    val channel = text("channel")
    val recipientType = text("recipient_type")
    val recipientId = text("recipient_id")
    val payload = jsonb<DispatchContent>("payload", dispatchJson)
    val state = text("state").default("READY")
    val attempt = integer("attempt").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("delivery_state_next_attempt_time_idx", false, state, nextAttemptTime)
        index("delivery_inbox_event_id_idx", false, inboxEventId)
    }
}
