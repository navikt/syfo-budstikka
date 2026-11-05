package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

@Suppress("unused")
object InboxMessageTable : Table("inbox_message") {
    val eventId = javaUUID("event_id").databaseGenerated()
    val content = jsonb<DispatchContent>("content", dispatchJson)
    val reference = text("reference")
    val state = text("state").default(InboxMessageState.RECEIVED.name)
    val claimToken = javaUUID("claim_token").nullable()
    val dropReason = text("drop_reason").nullable()
    val attempt = integer("attempt").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val receivedAt = timestamp("received_at").defaultExpression(CurrentTimestamp)
    val processedAt = timestamp("processed_at").nullable()
    val errorMessage = text("error_message").nullable()
    val waitReason = text("wait_reason").nullable()

    /** Set only on a row derived from another inbox row; it names the source (ADR 0018). */
    val sourceEventId = javaUUID("source_event_id").nullable()

    override val primaryKey = PrimaryKey(eventId)

    init {
        check("inbox_message_claim_token_state_check") { (state eq InboxMessageState.CLAIMED.name) eq claimToken.isNotNull() }
        check("inbox_message_next_attempt_time_state_check") {
            (state notInList listOf(InboxMessageState.CLAIMED.name, InboxMessageState.WAIT.name)) or nextAttemptTime.isNotNull()
        }
        index("inbox_message_state_next_attempt_time_idx", false, state, nextAttemptTime)
        index("inbox_message_received_at_event_id_idx", false, receivedAt, eventId)
        index(
            "inbox_message_microfrontend_key_idx",
            false,
            functions = listOf(contentText("personIdentifier"), contentText("microfrontendId")),
            filterCondition = { contentText("microfrontendId").isNotNull() },
        )
    }

    /** `content ->> 'field'`, the expression the microfrontend key index is built on. */
    private fun contentText(field: String) = CustomOperator<String>("->>", TextColumnType(), content, stringLiteral(field))
}
