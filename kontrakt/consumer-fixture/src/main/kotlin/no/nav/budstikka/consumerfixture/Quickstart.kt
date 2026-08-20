package no.nav.budstikka.consumerfixture

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.nav.budstikka.contract.Arbeidsgivervarsel
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Varseltype
import org.apache.kafka.clients.producer.ProducerRecord

private const val NOTIFICATION_ID = "synthetic-notification-0001"
private val persistedEventIds = mutableMapOf<String, EventId>()

fun main() {
    val eventId =
        persistedEventIds.getOrPut(NOTIFICATION_ID) {
            EventId(UUID.fromString("00000000-0000-4000-8000-000000000101"))
        }
    check(persistedEventIds.getValue(NOTIFICATION_ID) == eventId)

    val encoded =
        Budstikka.brukervarselCreate(
            eventId = eventId,
            reference = "synthetic-reference-0001",
            sykmeldt = PersonIdentifier("00000000000"),
            varseltype = Varseltype.BESKJED,
            text = "SYNTETISK-VARSELTEKST",
        )
    val record = ProducerRecord(encoded.topic, encoded.key, encoded.value)
    encoded.headerBytes().forEach { (name, value) -> record.headers().add(name, value) }

    check(record.topic() == "team-esyfo.budstikka.v1")
    check(record.key() == "00000000000")
    val expectedJson =
        """{"reference":"synthetic-reference-0001","content":{"type":"BrukervarselCreate","personIdentifier":"00000000000","varseltype":"BESKJED","text":"SYNTETISK-VARSELTEKST","link":null,"visibleUntil":null,"externalVarsling":null,"brevFallback":null,"sendingWindow":"BUDSTIKKA_OPENING_HOURS"}}"""
    check(record.value() == expectedJson)
    check(Json.parseToJsonElement(record.value()).jsonObject["reference"]?.jsonPrimitive?.content == "synthetic-reference-0001")
    check(record.headers().lastHeader("eventId")?.value()?.toString(Charsets.UTF_8) == eventId.toString())

    listOf(
        Arbeidsgivervarsel.NarmesteLeder(PersonIdentifier("00000000000")) to
            """"mottaker":{"type":"NarmesteLeder","sykmeldt":"00000000000",""",
        Arbeidsgivervarsel.AltinnRessurs("nav_syfo_dialogmote") to
            """"mottaker":{"type":"AltinnRessurs","resource":"nav_syfo_dialogmote",""",
    ).forEach { (recipient, recipientJson) ->
        val arbeidsgivervarsel =
            Budstikka.arbeidsgivervarselCreate(
                eventId = eventId,
                reference = "synthetic-employer-reference",
                orgnummer = Orgnummer("999999999"),
                recipient = recipient,
                tag = "Dialogmøte",
                text = "SYNTETISK-VARSELTEKST",
                link = "https://nav.no/ag",
            )
        check(arbeidsgivervarsel.topic == "team-esyfo.budstikka.v1")
        check(arbeidsgivervarsel.key == "999999999")
        check(arbeidsgivervarsel.headers["eventId"] == eventId.toString())
        check(arbeidsgivervarsel.value.contains(recipientJson))
    }
}
