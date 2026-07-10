package no.nav.budstikka.infrastructure.database.leveranse

import no.nav.budstikka.domain.formidling.Formidlingsinnhold
import no.nav.budstikka.domain.formidling.formidlingJson
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Speiler `V3__leveranse.sql` eksakt (jf. #50-lærdommen: skjema↔mapping må stemme, ellers
 * feiler det først i runtime mot ekte Postgres). Domenekolonner beholder domenespråket
 * (`referanse`/`operasjon`/`kanal`/`mottaker_*`); infra-kolonner følger søster-tabellen
 * `inbox_formidling` (`state`/`attempt`/`next_attempt_time`/`created_at`).
 */
object LeveranseTable : Table("leveranse") {
    val id = javaUUID("id").databaseGenerated()
    val inboxEventId = javaUUID("inbox_event_id").nullable()
    val referanse = text("referanse")
    val operasjon = text("operasjon")
    val kanal = text("kanal")
    val mottakerType = text("mottaker_type")
    val mottakerId = text("mottaker_id")
    val payload = jsonb<Formidlingsinnhold>("payload", formidlingJson)
    val state = text("state").default("READY")
    val attempt = integer("attempt").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index("leveranse_state_next_attempt_time_idx", false, state, nextAttemptTime)
        index("leveranse_inbox_event_id_idx", false, inboxEventId)
    }
}
