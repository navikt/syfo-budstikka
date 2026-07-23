package no.nav.budstikka.domain.dispatch.dsl

import no.nav.budstikka.domain.dispatch.Dispatch
import no.nav.budstikka.domain.dispatch.DispatchContent
import no.nav.budstikka.domain.dispatch.DispatchHeader
import no.nav.budstikka.domain.dispatch.dispatchJson
import java.util.UUID

/**
 * Kafka-fritt resultat av DSL-en (ADR 0011, B64): alt en produsent trenger for å bygge sin egen
 * `ProducerRecord`, uten at kontraktbiblioteket avhenger av `kafka-clients`.
 *
 * - [partitionKey]: Kafka record key (= mottakerankeret, B5).
 * - [value]: `dispatchJson`-serialisert [Dispatch] (JSON-streng).
 * - [eventId]: header-verdi, generert av biblioteket (dedup/korrelasjon, ADR 0008). Returneres slik
 *   at produsenten kan logge sin egen eventId for kryss-system-korrelasjon (B45).
 * - [headerName]: navnet headeren skal settes under (= [DispatchHeader.EVENT_ID]).
 *
 * Typisk bruk hos produsent:
 * ```
 * val e = brukervarselCreate(...) { ... }
 * ProducerRecord(topic, e.partitionKey, e.value)
 *     .apply { headers().add(e.headerName, e.eventId.toByteArray()) }
 * ```
 */
data class EncodedDispatch(
    val partitionKey: String,
    val value: String,
    val eventId: String,
    val headerName: String = DispatchHeader.EVENT_ID,
)

/** Pakker innholdet i konvolutten, serialiserer og genererer en ny eventId */
internal fun DispatchContent.encode(reference: String): EncodedDispatch {
    val dispatch = Dispatch(reference = reference, content = this)
    return EncodedDispatch(
        partitionKey = partitionKey,
        value = dispatchJson.encodeToString(dispatch),
        eventId = UUID.randomUUID().toString(),
    )
}
