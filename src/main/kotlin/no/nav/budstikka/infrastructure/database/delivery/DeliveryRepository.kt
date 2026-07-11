package no.nav.budstikka.infrastructure.database.delivery

import no.nav.budstikka.domain.decision.DeliveryDraft
import no.nav.budstikka.domain.decision.Recipient
import org.jetbrains.exposed.v1.jdbc.insert
import java.util.UUID
import kotlin.time.Clock

/**
 * Skriver frosne [DeliveryDraft] som `delivery`-rader. Én inbox-hendelse gir 0..N leveranser.
 * Åpner IKKE egen transaksjon: kjøres inne i
 * [no.nav.budstikka.infrastructure.database.config.TransactionRunner.transaction] sammen med inbox-
 * status-overgangen, slik at beslutnings-workeren (#56) effektuerer én melding alt-eller-ingenting.
 * `id`/`state`/`attempt` fylles av DB-defaults (uuidv7 / 'READY' / 0).
 */
interface DeliveryRepository {
    fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    )
}

class DeliveryRepositoryImpl : DeliveryRepository {
    override fun saveInTransaction(
        inboxEventId: UUID,
        draft: List<DeliveryDraft>,
    ) {
        draft.forEach { draftEntry ->
            val (type, id) = draftEntry.recipient.toColumns()
            DeliveryTable.insert {
                it[DeliveryTable.inboxEventId] = inboxEventId
                it[DeliveryTable.reference] = draftEntry.reference
                it[DeliveryTable.operation] = draftEntry.operation.name
                it[DeliveryTable.channel] = draftEntry.channel.name
                it[DeliveryTable.recipientType] = type
                it[DeliveryTable.recipientId] = id
                it[DeliveryTable.payload] = draftEntry.content
                it[DeliveryTable.createdAt] = Clock.System.now()
            }
        }
    }
}

private fun Recipient.toColumns(): Pair<String, String> =
    when (this) {
        is Recipient.Person -> "PERSON" to ident.value
        is Recipient.Virksomhet -> "VIRKSOMHET" to orgnummer.value
    }
