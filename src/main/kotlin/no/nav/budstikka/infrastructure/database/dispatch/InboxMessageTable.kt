package no.nav.budstikka.infrastructure.database.dispatch

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import no.nav.budstikka.infrastructure.database.config.timestamptz
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
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
    val nextAttemptTime = timestamptz("next_attempt_time").nullable()
    val receivedAt = timestamptz("received_at").defaultExpression(CurrentTimestamp)
    val processedAt = timestamptz("processed_at").nullable()
    val errorMessage = text("error_message").nullable()
    val waitReason = text("wait_reason").nullable()

    /** Set only on a row derived from another inbox row; it names the source. */
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
            "inbox_message_source_event_id_idx",
            false,
            sourceEventId,
            filterCondition = { sourceEventId.isNotNull() },
        )
        index(
            "inbox_message_microfrontend_key_idx",
            false,
            functions = listOf(content.textField("personIdentifier")),
            indexType = "HASH",
            filterCondition = { content.textField("microfrontendId").isNotNull() },
        )
    }

    /** Rows for this microfrontend. The key index finds the person's rows; microfrontendId is compared exactly. */
    fun microfrontendIs(
        personIdentifier: String,
        microfrontendId: String,
    ): Op<Boolean> =
        (content.textField("personIdentifier") eq personIdentifier) and
            (content.textField("microfrontendId") eq microfrontendId)
}

/** Keeps JSON field queries, including aliased rows, compatible with the microfrontend key index. */
internal fun Expression<DispatchContent>.textField(field: String) =
    CustomOperator<String>("->>", TextColumnType(), this, stringLiteral(field))
