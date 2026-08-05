// The facade is the ONE place in the contract that is allowed to touch the wire: it exists precisely
// to assemble envelope, canonical JSON, partition key, topic and header so a Produsent never has to.
// Opting in here, and only here, is what makes the raw API safe to gate everywhere else.
@file:OptIn(InternalBudstikkaWire::class)

package no.nav.budstikka.contract

import kotlin.time.Instant

/**
 * The producer-facing API of the Budstikka contract, and the only entry point a Produsent needs:
 * one named function per message variant that budstikka delivers end to end today. Envelope, JSON
 * configuration, partition key, topic and header names are library mechanics and stay behind it.
 *
 * ```kotlin
 * val eventId = EventId.new()          // persist together with your own work, before sending
 * val encoded = Budstikka.brukervarselCreate(
 *     eventId = eventId,
 *     reference = "dialogmote-innkalling-42",
 *     sykmeldt = PersonIdentifier(fnr),
 *     varseltype = Varseltype.OPPGAVE,
 *     text = "Du har fått en innkalling til dialogmøte",
 * )
 * ```
 *
 * Variants a Produsent cannot send yet are deliberately absent, even where the wire type exists:
 * DittSykefravaer and Arbeidsgivervarsel have no registered channel in budstikka, so a dispatch would
 * be accepted and never delivered. They are added here when their channel is finished.
 *
 * Every function validates required identifiers, references and explicitly constrained values before
 * encoding, and fails with [IllegalArgumentException] naming the offending parameter — never its
 * value, since these values are person data and free text. Semantic constraints owned downstream
 * (for example whether a `reference` is actually unique) are not checked here.
 */
object Budstikka {
    /** The topic that carries this contract. It is the same in every environment. */
    const val TOPIC: String = "team-esyfo.budstikka.v1"

    /**
     * Brukervarsel to the Sykmeldt on Min side. Brukervarsel is Min side's product name for its
     * notifications (the varsel-API), and this function is named after that product — it is not a
     * catch-all for every channel that can reach the Sykmeldt. Ditt Sykefravær, Brev and
     * microfrontends are separate variants.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for the notification, used to inactivate it later
     *   ([brukervarselInactivate]) and as the varselId on Min side. Must be unique per notification.
     * @param sykmeldt the person who receives the notification.
     * @param text the notification text shown to the person.
     * @param link where the notification takes the person; omit for a notification without a target.
     * @param visibleUntil when Min side stops showing the notification; omit to keep it until inactivated.
     * @param externalVarsling adds SMS or email in addition to Min side; omit for Min side only.
     * @param brevFallback lets budstikka send the document as a Brev instead when the person has
     *   Reservasjon — printed and posted, like [brevCreate], never routed digitally. Requires a
     *   `journalpostId` you have already created.
     * @param sendingWindow when the notification may leave budstikka.
     */
    fun brukervarselCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        varseltype: Varseltype,
        text: String,
        link: String? = null,
        visibleUntil: Instant? = null,
        externalVarsling: ExternalVarsling? = null,
        brevFallback: BrevFallback? = null,
        sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(text, "text")
        requireNullOrNotBlank(link, "link")
        brevFallback?.let { requireNotBlank(it.journalpostId, "brevFallback.journalpostId") }
        return BrukervarselCreate(
            personIdentifier = sykmeldt,
            varseltype = varseltype,
            text = text,
            link = link,
            visibleUntil = visibleUntil,
            externalVarsling = externalVarsling,
            brevFallback = brevFallback,
            sendingWindow = sendingWindow,
        ).encode(eventId, reference)
    }

    /**
     * Closes a Brukervarsel created earlier.
     *
     * @param reference the reference of the [brukervarselCreate] to close.
     * @param sykmeldt the same person as in the create; it anchors both on one partition.
     */
    fun brukervarselInactivate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        return BrukervarselInactivate(reference = reference, sykmeldt = sykmeldt).encode(eventId, reference)
    }

    /**
     * An in-app activity notification about the Sykmeldt in Dine Sykmeldte. The function is named
     * after the channel it delivers on; the wire variant keeps budstikka's established domain name
     * [LedervarselCreate]. Naming the recipient instead would mislead: the leader can also be
     * reached through Arbeidsgivervarsel once that channel is finished, and this channel has no
     * external carrier, so there is no SMS or email option here. You pass the Sykmeldt and the
     * organisation, never a leader's personident: budstikka forwards `(sykmeldt, orgnummer,
     * oppgavetype)` to Dine Sykmeldte as an activity notification and does no Nærmeste leder lookup
     * of its own.
     *
     * @param sykmeldt the employee the notification is about; also the partition anchor.
     * @param orgnummer the organisation the employment belongs to.
     * @param oppgavetype required by Dine Sykmeldte to group and deduplicate the notification.
     */
    fun dineSykmeldteVarselCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
        oppgavetype: Oppgavetype,
        text: String,
        link: String? = null,
        visibleUntil: Instant? = null,
        sendingWindow: SendingWindow = SendingWindow.ONGOING,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        orgnummer.requireOrgnummer()
        requireNotBlank(text, "text")
        requireNullOrNotBlank(link, "link")
        return LedervarselCreate(
            sykmeldt = sykmeldt,
            orgnummer = orgnummer,
            oppgavetype = oppgavetype,
            text = text,
            link = link,
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        ).encode(eventId, reference)
    }

    /**
     * Closes a Dine Sykmeldte varsel created earlier with [dineSykmeldteVarselCreate].
     *
     * @param sykmeldt the same employee as in the create; the contract never carries a leader's
     *   personident.
     */
    fun dineSykmeldteVarselInactivate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        return LedervarselInactivate(reference = reference, sykmeldt = sykmeldt).encode(eventId, reference)
    }

    /**
     * Brev: a printed letter to the Sykmeldt. Budstikka always forces central print
     * (dokdistfordeling with `tvingKanal: PRINT`); this is deliberately not the ordinary dokdist
     * route, so the letter goes on paper even when the person could receive it digitally in
     * Digipost. A Brev cannot be inactivated once sent, so there is no matching inactivate
     * function.
     *
     * @param journalpostId the journalpost you have already created for the document.
     */
    fun brevCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        journalpostId: String,
        distributionType: DistributionType = DistributionType.IMPORTANT,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(journalpostId, "journalpostId")
        return BrevCreate(
            personIdentifier = sykmeldt,
            journalpostId = journalpostId,
            distributionType = distributionType,
        ).encode(eventId, reference)
    }

    /**
     * Makes a microfrontend visible for the Sykmeldt on Min side. This is a visibility switch, not a
     * notification: turn it off again with [microfrontendDisable].
     *
     * @param reference your own id for this enable/disable pair, used for correlation and bookkeeping
     *   in the envelope. It is not what identifies the microfrontend downstream: Min side matches on
     *   `(sykmeldt, microfrontendId)`, and unlike [brukervarselInactivate] these two functions do not
     *   use reference-based inactivate matching. Required all the same, so every dispatch is traceable.
     * @param microfrontendId the id Min side knows the microfrontend by.
     * @param visibleUntil when Min side hides it automatically; omit to keep it until disabled.
     */
    fun microfrontendEnable(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        microfrontendId: String,
        visibleUntil: Instant? = null,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(microfrontendId, "microfrontendId")
        return MicrofrontendEnable(
            personIdentifier = sykmeldt,
            microfrontendId = microfrontendId,
            visibleUntil = visibleUntil,
        ).encode(eventId, reference)
    }

    /**
     * Hides a microfrontend previously made visible with [microfrontendEnable].
     *
     * @param reference your own id for this enable/disable pair, used for correlation and bookkeeping
     *   in the envelope. It does not have to match the reference of the [microfrontendEnable] it hides:
     *   Min side matches on `(sykmeldt, microfrontendId)`, not on reference.
     * @param microfrontendId the same id as in the enable; it is what Min side hides.
     */
    fun microfrontendDisable(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        microfrontendId: String,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(microfrontendId, "microfrontendId")
        return MicrofrontendDisable(
            personIdentifier = sykmeldt,
            microfrontendId = microfrontendId,
        ).encode(eventId, reference)
    }
}

/**
 * The single place the envelope, the canonical JSON, the partition key, the topic and the header
 * name are assembled, so no producer-facing function can get one of them wrong on its own.
 */
private fun DispatchContent.encode(
    eventId: EventId,
    reference: String,
): EncodedDispatch =
    EncodedDispatch(
        topic = Budstikka.TOPIC,
        key = partitionKey,
        value = dispatchJson.encodeToString(Dispatch(reference = reference, content = this)),
        eventId = eventId,
        headers = mapOf(DispatchHeader.EVENT_ID to eventId.toString()),
    )

/*
 * Validation names the parameter and never echoes its value: these arguments are personidenter,
 * orgnummer and notification text, and an exception message may travel straight into a log.
 */

private const val PERSON_IDENTIFIER_LENGTH = 11
private const val ORGNUMMER_LENGTH = 9

private fun requireReference(reference: String) = requireNotBlank(reference, "reference")

private fun requireNotBlank(
    value: String,
    parameter: String,
) = require(value.isNotBlank()) { "$parameter must not be blank" }

private fun requireNullOrNotBlank(
    value: String?,
    parameter: String,
) = require(value == null || value.isNotBlank()) { "$parameter must not be blank when set" }

private fun PersonIdentifier.requirePersonIdentifier(parameter: String) =
    require(value.length == PERSON_IDENTIFIER_LENGTH && value.all(Char::isDigit)) {
        "$parameter must be $PERSON_IDENTIFIER_LENGTH digits"
    }

private fun Orgnummer.requireOrgnummer() =
    require(value.length == ORGNUMMER_LENGTH && value.all(Char::isDigit)) {
        "orgnummer must be $ORGNUMMER_LENGTH digits"
    }
