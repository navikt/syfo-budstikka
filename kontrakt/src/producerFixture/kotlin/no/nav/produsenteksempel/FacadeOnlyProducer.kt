package no.nav.produsenteksempel

import no.nav.budstikka.contract.Arbeidsgivervarsel
import no.nav.budstikka.contract.BrevFallback
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.DistributionType
import no.nav.budstikka.contract.EncodedDispatch
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.ExternalChannel
import no.nav.budstikka.contract.ExternalNotification
import no.nav.budstikka.contract.Oppgavetype
import no.nav.budstikka.contract.Orgnummer
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.SendingWindow
import no.nav.budstikka.contract.Varseltype
import kotlin.time.Instant

/**
 * Stands in for a producing application: its own package, and not one `@OptIn` in sight.
 *
 * The file is compiled by `:kontrakt:check` and by nothing else — it ships in no artifact and asserts
 * nothing at runtime. Its whole job is to fail the build the day a type a Produsent legitimately needs
 * slips behind `@InternalBudstikkaWire`. Every parameter type, return type and default below belongs
 * to the supported producer surface, so a compile error here is the gate having gone too far.
 */
object FacadeOnlyProducer {
    private val sykmeldt = PersonIdentifier("00000000000")
    private val orgnummer = Orgnummer("999999999")
    private val visibleUntil = Instant.parse("2026-01-01T00:00:00Z")

    fun brukervarsel(eventId: EventId): EncodedDispatch =
        Budstikka.brukervarselCreate(
            eventId = eventId,
            reference = "fixture-1",
            sykmeldt = sykmeldt,
            varseltype = Varseltype.OPPGAVE,
            text = "SYNTETISK-VARSELTEKST",
            link = "https://nav.no/syk",
            visibleUntil = visibleUntil,
            externalVarsling = externalVarsling(),
            brevFallback = BrevFallback(journalpostId = "jp-1", distributionType = DistributionType.OTHER),
            sendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
        )

    fun brukervarselInactivate(eventId: EventId): EncodedDispatch =
        Budstikka.brukervarselInactivate(eventId = eventId, reference = "fixture-1", sykmeldt = sykmeldt)

    fun dineSykmeldteVarsel(eventId: EventId): EncodedDispatch =
        Budstikka.dineSykmeldteVarselCreate(
            eventId = eventId,
            reference = "fixture-2",
            sykmeldt = sykmeldt,
            orgnummer = orgnummer,
            oppgavetype = Oppgavetype.DIALOGMOTE_INNKALLING,
            text = "SYNTETISK-VARSELTEKST",
            link = "https://nav.no/dine-sykmeldte",
            visibleUntil = visibleUntil,
            sendingWindow = SendingWindow.ONGOING,
        )

    fun dineSykmeldteVarselInactivate(eventId: EventId): EncodedDispatch =
        Budstikka.dineSykmeldteVarselInactivate(eventId = eventId, reference = "fixture-2", sykmeldt = sykmeldt)

    fun arbeidsgivervarselToNarmesteLeder(eventId: EventId): EncodedDispatch =
        Budstikka.arbeidsgivervarselCreate(
            eventId = eventId,
            reference = "fixture-arbeidsgiver-1",
            orgnummer = orgnummer,
            recipient = Arbeidsgivervarsel.NarmesteLeder(sykmeldt),
            htmlEmail =
                Arbeidsgivervarsel.HtmlEmailNotification(
                    emailTitle = "Syntetisk e-posttittel",
                    emailHtmlBody = "<p>SYNTETISK-E-POSTTEKST</p>",
                ),
            tag = "Dialogmøte",
            text = "SYNTETISK-VARSELTEKST",
            link = "https://nav.no/ag",
        )

    fun arbeidsgivervarselToAltinn(eventId: EventId): EncodedDispatch =
        Budstikka.arbeidsgivervarselCreate(
            eventId = eventId,
            reference = "fixture-arbeidsgiver-2",
            orgnummer = orgnummer,
            recipient = Arbeidsgivervarsel.AltinnResource("nav_syfo_dialogmote"),
            tag = "Oppfølging",
            text = "SYNTETISK-VARSELTEKST",
            link = "https://nav.no/ag",
        )

    fun brev(eventId: EventId): EncodedDispatch =
        Budstikka.brevCreate(
            eventId = eventId,
            reference = "fixture-3",
            sykmeldt = sykmeldt,
            journalpostId = "jp-1",
            distributionType = DistributionType.IMPORTANT,
        )

    fun microfrontendEnable(eventId: EventId): EncodedDispatch =
        Budstikka.microfrontendEnable(
            eventId = eventId,
            reference = "fixture-4",
            sykmeldt = sykmeldt,
            microfrontendId = "syk-dialog",
            visibleUntil = visibleUntil,
        )

    fun microfrontendDisable(eventId: EventId): EncodedDispatch =
        Budstikka.microfrontendDisable(
            eventId = eventId,
            reference = "fixture-4",
            sykmeldt = sykmeldt,
            microfrontendId = "syk-dialog",
        )

    /** Everything a Produsent needs to build a Kafka record, still without touching the envelope. */
    fun record(encoded: EncodedDispatch): Triple<String, String, Map<String, ByteArray>> =
        Triple(encoded.topic, encoded.key, encoded.headerBytes())

    /** The topic name is public on purpose: a Produsent needs it to declare its Kafkarator access. */
    fun topic(): String = Budstikka.TOPIC

    /** A fresh eventId, persisted by the Produsent before the first send and reused on every retry. */
    fun newEventId(): EventId = EventId.new()

    private fun externalVarsling(): ExternalNotification {
        val both = ExternalNotification.smsAndEmail(smsText = "SYNTETISK-SMSTEKST", emailTitle = "SYNTETISK-EPOSTTITTEL")
        val channels: Set<ExternalChannel> = both.channels
        return if (ExternalChannel.SMS in channels) both else ExternalNotification.emailOnly()
    }
}
