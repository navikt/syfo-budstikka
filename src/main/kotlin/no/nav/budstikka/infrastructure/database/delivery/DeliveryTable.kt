package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.contract.DispatchContent
import no.nav.budstikka.contract.dispatchJson
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.datetime.CurrentTimestamp
import org.jetbrains.exposed.v1.datetime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

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
    val claimToken = javaUUID("claim_token").nullable()
    val attempt = integer("attempt").default(0)
    val nextAttemptTime = timestamp("next_attempt_time").nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)
    val errorMessage = text("error_message").nullable()
    val snapshotState = text("snapshot_state").default("LEGACY")
    val leaderFnr = text("leader_fnr").nullable()
    val leaderEmails = jsonb<List<String>>("leader_emails", dispatchJson).nullable()
    val leaderRelationId = javaUUID("leader_relation_id").nullable()
    val nameFirst = text("name_first").nullable()
    val nameMiddle = text("name_middle").nullable()
    val nameLast = text("name_last").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        check("delivery_snapshot_state_check") { snapshotState inList listOf("LEGACY", "RESOLVED") }
        check("delivery_snapshot_name_check") {
            (nameFirst.isNull() and nameMiddle.isNull() and nameLast.isNull()) or (nameFirst.isNotNull() and nameLast.isNotNull())
        }
        check("delivery_snapshot_leader_check") {
            object : Op<Boolean>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                    queryBuilder.append(
                        """
                        snapshot_state = 'LEGACY'
                        OR channel <> 'ARBEIDSGIVERVARSEL'
                        OR operation <> 'CREATE'
                        OR payload -> 'mottaker' ->> 'type' IS DISTINCT FROM 'NarmesteLeder'
                        OR (
                            leader_fnr IS NOT NULL AND length(leader_fnr) = 11
                            AND leader_relation_id IS NOT NULL
                            AND leader_emails IS NOT NULL
                            AND CASE WHEN jsonb_typeof(leader_emails) = 'array' THEN
                                CASE WHEN payload -> 'mottaker' -> 'externalVarsling' IS NOT NULL
                                          AND payload -> 'mottaker' -> 'externalVarsling' <> 'null'::jsonb
                                     THEN jsonb_array_length(leader_emails) > 0
                                     ELSE TRUE END
                                ELSE FALSE END
                        )
                        """.trimIndent(),
                    )
                }
            }
        }
        check("delivery_snapshot_kalenderavtale_check") {
            object : Op<Boolean>() {
                override fun toQueryBuilder(queryBuilder: QueryBuilder) {
                    queryBuilder.append(
                        """
                        channel <> 'KALENDERAVTALE'
                        OR (
                            snapshot_state = 'RESOLVED'
                            AND (
                                payload -> 'mottaker' ->> 'type' IS DISTINCT FROM 'NarmesteLeder'
                                OR NOT (
                                    operation = 'CREATE'
                                    OR (payload -> 'mottaker' -> 'externalVarsling' IS NOT NULL
                                        AND payload -> 'mottaker' -> 'externalVarsling' <> 'null'::jsonb)
                                    OR (payload -> 'saksstatus' IS NOT NULL AND payload -> 'saksstatus' <> 'null'::jsonb)
                                )
                                OR (
                                    leader_fnr IS NOT NULL AND length(leader_fnr) = 11
                                    AND leader_relation_id IS NOT NULL
                                    AND leader_emails IS NOT NULL
                                    AND CASE WHEN jsonb_typeof(leader_emails) = 'array' THEN
                                        CASE WHEN payload -> 'mottaker' -> 'externalVarsling' IS NOT NULL
                                                  AND payload -> 'mottaker' -> 'externalVarsling' <> 'null'::jsonb
                                             THEN jsonb_array_length(leader_emails) > 0
                                             ELSE TRUE END
                                        ELSE FALSE END
                                )
                            )
                        )
                        """.trimIndent(),
                    )
                }
            }
        }
        check("delivery_claim_token_state_check") { (state eq DeliveryState.CLAIMED.name) eq claimToken.isNotNull() }
        check("delivery_next_attempt_time_state_check") { (state neq DeliveryState.CLAIMED.name) or nextAttemptTime.isNotNull() }
        index("delivery_state_next_attempt_time_idx", false, state, nextAttemptTime)
        index("delivery_inbox_event_id_idx", false, inboxEventId)
        index(
            "delivery_created_at_id_sent_failed_idx",
            false,
            createdAt,
            id,
            filterCondition = { state inList listOf(DeliveryState.SENT.name, DeliveryState.FAILED.name) },
        )
    }
}
