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
 * Variants a Produsent cannot send through the facade yet are deliberately absent, even where the
 * wire type exists. DittSykefravaer has no registered channel.
 *
 * Every function validates required identifiers, references and mandatory values before
 * encoding, and fails with [IllegalArgumentException] naming the offending parameter — never its
 * value, since these values are person data and free text. Semantic constraints owned downstream
 * (for example whether a `reference` is actually unique) are not checked here.
 */
object Budstikka {
    /** The canonical contract topic producers use in each Kafka pool. */
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
     * @param varseltype the type Min side uses to present the notification.
     * @param text the notification text shown to the person.
     * @param link where the notification takes the person; omit for a notification without a target.
     * @param visibleUntil when Min side stops showing the notification; omit to keep it until inactivated.
     * @param externalVarsling adds SMS or email in addition to Min side; omit for Min side only.
     * @param brevFallback lets budstikka send the document through dokumentdistribusjon instead
     *   when the person cannot be notified digitally; dokdist picks the channel, which for a
     *   reserved person means paper. Requires a `journalpostId` you have already created.
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
        externalVarsling: ExternalNotification? = null,
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
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
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
     * reached through Arbeidsgivervarsel, and this channel has no
     * external carrier, so there is no SMS or email option here. You pass the Sykmeldt and the
     * organisation, never a leader's personident: budstikka forwards `(sykmeldt, orgnummer,
     * oppgavetype)` to Dine Sykmeldte as an activity notification and does no Nærmeste leder lookup
     * of its own.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this notification, used when it is inactivated later.
     * @param sykmeldt the employee the notification is about; also the partition anchor.
     * @param orgnummer the organisation the employment belongs to.
     * @param oppgavetype required by Dine Sykmeldte to group and deduplicate the notification.
     * @param text the activity notification text.
     * @param link target for the activity notification; omit when it has no target.
     * @param visibleUntil when Dine Sykmeldte stops showing the notification; omit to keep it visible.
     * @param sendingWindow when the notification may leave Budstikka.
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
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference the reference of the create notification to close.
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
     * Sends an Arbeidsgivervarsel through either the Nærmeste leder or Altinn resource path.
     * A missing active Nærmeste leder is terminal. When external notification was requested, a
     * missing leader email address is also terminal.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this notification, used for correlation and bookkeeping.
     * @param orgnummer the organisation that owns the notification and the partition anchor.
     * @param recipient either the active Nærmeste leder for a Sykmeldt or everyone with an Altinn
     *   resource. External notification is optional; when set, Nærmeste leder requires email title
     *   and text, while Altinn resource also requires SMS text.
     * @param tag the notification category used by the recipient channel; unlike [messageType], it
     *   does not choose whether the notification is a beskjed or oppgave. The producer selects the
     *   downstream value, which must match its registered merkelapp in Arbeidsgivernotifikasjoner;
     *   otherwise delivery fails terminally. For an Altinn recipient, its resource must likewise
     *   match the producer's registered Altinn resource.
     * @param text the notification text shown to the recipient.
     * @param link required target for the notification.
     * @param messageType whether the notification is a beskjed or oppgave; defaults to BESKJED.
     * @param caseAssociation optional producer-owned case identifier for downstream grouping.
     * @param visibleUntil when the recipient channel stops showing the notification; omit to keep
     *   it until inactivated.
     * @param sendingWindow when the notification may leave Budstikka; defaults to Budstikka opening
     *   hours.
     */
    fun arbeidsgivervarselCreate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        recipient: Arbeidsgivervarsel.Recipient,
        tag: String,
        text: String,
        link: String,
        messageType: Arbeidsgivervarsel.MessageType = Arbeidsgivervarsel.MessageType.BESKJED,
        caseAssociation: Arbeidsgivervarsel.CaseAssociation? = null,
        visibleUntil: Instant? = null,
        sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
    ): EncodedDispatch {
        requireReference(reference)
        orgnummer.requireOrgnummer()
        requireNotBlank(tag, "tag")
        requireNotBlank(text, "text")
        requireNotBlank(link, "link")
        caseAssociation?.let { requireNotBlank(it.caseId, "caseAssociation.caseId") }
        return ArbeidsgivervarselCreate(
            orgnummer = orgnummer,
            recipient = recipient.toWireRecipient(),
            tag = tag,
            text = text,
            link = link,
            meldingstype = messageType.toWireMessageType(),
            sakstilknytning = caseAssociation?.let { Sakstilknytning(it.caseId) },
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        ).encode(eventId, reference)
    }

    /**
     * Brev: a document distributed to the Sykmeldt through dokumentdistribusjon. By default
     * dokdist picks the channel itself — digital mailbox (Digipost/e-Boks) for persons without
     * Reservasjon, central print otherwise. A Brev cannot be inactivated once sent, so there is
     * no matching inactivate function.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this document dispatch.
     * @param sykmeldt the person receiving the document and the partition anchor.
     * @param journalpostId the journalpost you have already created for the document.
     * @param distributionType distribution priority for the document.
     * @param tvingSentralPrint forces central print (dokdist's `tvingKanal: PRINT`) so the letter
     *   goes on paper even when the person could receive it digitally. This is the exception, not
     *   the default — in esyfovarsel only the aktivitetsplikt re-notification does it.
     */
    fun brevCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        journalpostId: String,
        distributionType: DistributionType = DistributionType.IMPORTANT,
        tvingSentralPrint: Boolean = false,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(journalpostId, "journalpostId")
        return BrevCreate(
            personIdentifier = sykmeldt,
            journalpostId = journalpostId,
            distributionType = distributionType,
            tvingSentralPrint = tvingSentralPrint,
        ).encode(eventId, reference)
    }

    /**
     * Makes a microfrontend visible for the Sykmeldt on Min side. This is a visibility switch, not a
     * notification: turn it off again with [microfrontendDisable].
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this enable/disable pair, used for correlation and bookkeeping
     *   in the envelope. It is not what identifies the microfrontend downstream: Min side matches on
     *   `(sykmeldt, microfrontendId)`, and unlike [brukervarselInactivate] these two functions do not
     *   use reference-based inactivate matching. Required all the same, so every dispatch is traceable.
     * @param sykmeldt the person for whom Min side makes the microfrontend visible.
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
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this enable/disable pair, used for correlation and bookkeeping
     *   in the envelope. It does not have to match the reference of the [microfrontendEnable] it hides:
     *   Min side matches on `(sykmeldt, microfrontendId)`, not on reference.
     * @param sykmeldt the person for whom Min side hides the microfrontend.
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

private fun Arbeidsgivervarsel.Recipient.toWireRecipient(): ArbeidsgiverRecipient =
    when (this) {
        is Arbeidsgivervarsel.NarmesteLeder -> {
            sykmeldt.requirePersonIdentifier("recipient.sykmeldt")
            NarmesteLeder(
                sykmeldt = sykmeldt,
                externalVarsling =
                    externalNotification?.let {
                        requireNotBlank(it.emailTitle, "recipient.externalNotification.emailTitle")
                        requireNotBlank(it.emailText, "recipient.externalNotification.emailText")
                        NarmesteLederExternalVarsling(it.emailTitle, it.emailText)
                    },
            )
        }
        is Arbeidsgivervarsel.AltinnResource ->
            AltinnResource(
                resource = resource.also { requireNotBlank(it, "recipient.resource") },
                externalVarsling =
                    externalNotification?.let {
                        requireNotBlank(it.emailTitle, "recipient.externalNotification.emailTitle")
                        requireNotBlank(it.emailText, "recipient.externalNotification.emailText")
                        requireNotBlank(it.smsText, "recipient.externalNotification.smsText")
                        AltinnExternalVarsling(it.emailTitle, it.emailText, it.smsText)
                    },
            )
    }

private fun Arbeidsgivervarsel.MessageType.toWireMessageType(): ArbeidsgiverMeldingstype =
    when (this) {
        Arbeidsgivervarsel.MessageType.BESKJED -> ArbeidsgiverMeldingstype.BESKJED
        Arbeidsgivervarsel.MessageType.OPPGAVE -> ArbeidsgiverMeldingstype.OPPGAVE
    }
