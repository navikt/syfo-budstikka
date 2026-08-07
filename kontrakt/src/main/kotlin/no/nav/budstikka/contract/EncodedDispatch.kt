package no.nav.budstikka.contract

/**
 * One dispatch, encoded and ready to send, with no dependency on `kafka-clients`: the Produsent owns
 * its own producer, configuration and lifecycle. Every field maps directly onto a Kafka record:
 *
 * ```kotlin
 * val encoded = Budstikka.brukervarselCreate(eventId = eventId, /* ... */)
 * val record = ProducerRecord(encoded.topic, encoded.key, encoded.value)
 * encoded.headerBytes().forEach { (name, value) -> record.headers().add(name, value) }
 * ```
 *
 * Construction is library-internal: an [EncodedDispatch] can only come from a [Budstikka] function,
 * so topic, key, headers and payload always agree.
 *
 * @property topic Kafka topic the dispatch belongs on.
 * @property key record key = the Recipient id, which keeps dispatches for one Recipient ordered on
 *   one partition. It is a personident or an orgnummer: treat it as person data and never log it.
 * @property value canonical JSON payload. Contains person data and free text: never log it.
 * @property eventId the id supplied by the caller, also present in [headers].
 * @property headers required record headers, as UTF-8 text.
 */
class EncodedDispatch internal constructor(
    val topic: String,
    val key: String,
    val value: String,
    val eventId: EventId,
    val headers: Map<String, String>,
) {
    /** Returns [headers] as the UTF-8 bytes Kafka expects, so the Produsent does not have to pick an encoding. */
    fun headerBytes(): Map<String, ByteArray> = headers.mapValues { (_, value) -> value.toByteArray(Charsets.UTF_8) }

    /**
     * Omits [key] and [value] because they contain identifiers and free text. Topic and eventId are
     * technical correlation values and safe to expose.
     */
    override fun toString(): String = "EncodedDispatch(topic=$topic, eventId=$eventId)"
}
