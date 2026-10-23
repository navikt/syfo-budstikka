// The facade is the ONE place in the contract that is allowed to touch the wire: it exists precisely
// to assemble envelope, canonical JSON, partition key, topic and header so a Produsent never has to.
// Opting in here, and only here, is what makes the raw API safe to gate everywhere else.
@file:OptIn(InternalBudstikkaWire::class)

package no.nav.budstikka.contract

import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
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
     * Sends an in-app notification to Ditt Sykefravær. The downstream contract fixes the
     * presentation to INFO and lets the recipient close the message. [messageType] is a stable,
     * analytics-visible producer category and must never contain personal data.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this notification, used when it is inactivated later.
     * @param sykmeldt the person who receives the notification and the ingress partition anchor.
     * @param text the notification text shown to the person.
     * @param messageType stable, non-person-identifying category required by Ditt Sykefravær.
     * @param link optional target for the notification.
     * @param visibleUntil when Ditt Sykefravær stops showing the notification.
     */
    fun dittSykefravaerCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        text: String,
        messageType: String,
        link: String? = null,
        visibleUntil: Instant? = null,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(text, "text")
        requireNotBlank(messageType, "messageType")
        requireNullOrNotBlank(link, "link")
        return DittSykefravaerCreate(
            personIdentifier = sykmeldt,
            text = text,
            messageType = messageType,
            link = link,
            visibleUntil = visibleUntil,
        ).encode(eventId, reference)
    }

    /**
     * Closes a Ditt Sykefravær notification created earlier with [dittSykefravaerCreate].
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference the reference of the create notification to close.
     * @param sykmeldt the same person as in the create; it anchors both on one partition.
     */
    fun dittSykefravaerInactivate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
    ): EncodedDispatch {
        requireReference(reference)
        sykmeldt.requirePersonIdentifier("sykmeldt")
        return DittSykefravaerInactivate(reference = reference, sykmeldt = sykmeldt).encode(eventId, reference)
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
     * @param sendingWindow when the notification may leave Budstikka.
     * @param visibleUntil when Dine Sykmeldte stops showing the notification; omit to keep it visible.
     */
    fun dineSykmeldteVarselCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
        oppgavetype: Oppgavetype,
        text: String,
        sendingWindow: SendingWindow = SendingWindow.ONGOING,
        visibleUntil: Instant? = null,
    ): EncodedDispatch =
        encodeDineSykmeldteVarselCreate(
            eventId = eventId,
            reference = reference,
            sykmeldt = sykmeldt,
            orgnummer = orgnummer,
            oppgavetype = oppgavetype,
            text = text,
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        )

    /** Compatibility overload for producers on 0.1.0–0.3.0. The ignored link was never used by Dine Sykmeldte. */
    @Deprecated(
        message = "Dine Sykmeldte ignores link; use the overload without link",
    )
    @Suppress("UNUSED_PARAMETER")
    fun dineSykmeldteVarselCreate(
        eventId: EventId,
        reference: String,
        sykmeldt: PersonIdentifier,
        orgnummer: Orgnummer,
        oppgavetype: Oppgavetype,
        text: String,
        link: String?,
        visibleUntil: Instant? = null,
        sendingWindow: SendingWindow = SendingWindow.ONGOING,
    ): EncodedDispatch =
        encodeDineSykmeldteVarselCreate(
            eventId = eventId,
            reference = reference,
            sykmeldt = sykmeldt,
            orgnummer = orgnummer,
            oppgavetype = oppgavetype,
            text = text,
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        )

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
     *   and plain-text email body, while Altinn resource also requires SMS text. Use the overloads
     *   with [Arbeidsgivervarsel.HtmlEmailNotification] for an explicit HTML email body.
     * @param tag the notification category used by the recipient channel; unlike [messageType], it
     *   does not choose whether the notification is a beskjed or oppgave. The producer selects the
     *   downstream value, which must match its registered merkelapp in Arbeidsgivernotifikasjoner;
     *   otherwise delivery fails terminally. For an Altinn recipient, its resource must likewise
     *   match the producer's registered Altinn resource.
     * @param text the notification text shown to the recipient.
     * @param link required target for the notification.
     * @param messageType whether the notification is a beskjed or oppgave; defaults to BESKJED.
     * @param caseAssociation optional producer-owned case identifier for downstream grouping.
     * @param visibleUntil despite the name, the time when Arbeidsgivernotifikasjoner permanently
     *   deletes the notification and its data from its database and Kafka. This cannot be undone
     *   and does not cancel external notifications already scheduled. Omit to use the existing
     *   four-month retention period.
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
     * Creates an Arbeidsgivervarsel for Nærmeste leder with an explicitly trusted HTML email.
     * [htmlEmail] is forwarded unchanged to Arbeidsgivernotifikasjoner. The ordinary overload keeps
     * its existing plain-text-and-escaping semantics. Create [recipient] without its optional
     * `externalNotification`; the two alternatives cannot be combined.
     */
    fun arbeidsgivervarselCreate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        recipient: Arbeidsgivervarsel.NarmesteLeder,
        htmlEmail: Arbeidsgivervarsel.HtmlEmailNotification,
        tag: String,
        text: String,
        link: String,
        messageType: Arbeidsgivervarsel.MessageType = Arbeidsgivervarsel.MessageType.BESKJED,
        caseAssociation: Arbeidsgivervarsel.CaseAssociation? = null,
        visibleUntil: Instant? = null,
        sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
    ): EncodedDispatch =
        encodeArbeidsgivervarsel(
            eventId = eventId,
            reference = reference,
            orgnummer = orgnummer,
            recipient = recipient.toHtmlWireRecipient(htmlEmail),
            tag = tag,
            text = text,
            link = link,
            messageType = messageType,
            caseAssociation = caseAssociation,
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        )

    /**
     * Creates an Arbeidsgivervarsel for an Altinn resource with an explicitly trusted HTML email
     * and the required [smsText]. The ordinary overload keeps its existing plain-text-and-escaping
     * semantics. Create [recipient] without its optional `externalNotification`; the two
     * alternatives cannot be combined.
     */
    fun arbeidsgivervarselCreate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        recipient: Arbeidsgivervarsel.AltinnResource,
        htmlEmail: Arbeidsgivervarsel.HtmlEmailNotification,
        smsText: String,
        tag: String,
        text: String,
        link: String,
        messageType: Arbeidsgivervarsel.MessageType = Arbeidsgivervarsel.MessageType.BESKJED,
        caseAssociation: Arbeidsgivervarsel.CaseAssociation? = null,
        visibleUntil: Instant? = null,
        sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
    ): EncodedDispatch =
        encodeArbeidsgivervarsel(
            eventId = eventId,
            reference = reference,
            orgnummer = orgnummer,
            recipient = recipient.toHtmlWireRecipient(htmlEmail, smsText),
            tag = tag,
            text = text,
            link = link,
            messageType = messageType,
            caseAssociation = caseAssociation,
            visibleUntil = visibleUntil,
            sendingWindow = sendingWindow,
        )

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
     * @param visibleUntil when Budstikka hides it again by sending a disable; omit to keep it until
     *   disabled. Must be in the future and at most 335 days ahead. The last command for the same
     *   `(sykmeldt, microfrontendId)` wins: a later enable or disable cancels a pending scheduled
     *   hide, and a new enable with a later [visibleUntil] extends the visibility.
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
        visibleUntil?.let { requireVisibleUntilWithinLimit(it) }
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

    /**
     * Creates a case in Arbeidsgivernotifikasjoner. Create it once per [caseId], before any content
     * that refers to it; content that arrives first is retried until the case exists.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this dispatch, used for correlation and bookkeeping.
     * @param orgnummer the organisation that owns the case and the partition anchor.
     * @param recipient the active Nærmeste leder for a Sykmeldt, or an Altinn resource. Content in
     *   the case must reach the same recipient.
     * @param tag the registered merkelapp; content in the case must use the same tag.
     * @param caseId your stable case identity, used downstream as grupperingsid. It can never be
     *   reused, not even after the case is deleted. Never use a personident.
     * @param title the case title shown to the recipient.
     * @param initialStatus the status the case starts in.
     * @param link optional target for the case; omit to use the case page in Min side arbeidsgiver.
     * @param hardDeleteAt when Arbeidsgivernotifikasjoner permanently deletes the case and all
     *   content in it. Omit to use the four-month retention period.
     */
    fun arbeidsgiversakCreate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        recipient: Arbeidsgiversak.Recipient,
        tag: String,
        caseId: String,
        title: String,
        initialStatus: Arbeidsgiversak.Status = Arbeidsgiversak.Status.RECEIVED,
        link: String? = null,
        hardDeleteAt: Instant? = null,
    ): EncodedDispatch {
        requireReference(reference)
        orgnummer.requireOrgnummer()
        requireNotBlank(tag, "tag")
        requireNotBlank(caseId, "caseId")
        requireNotBlank(title, "title")
        requireNullOrNotBlank(link, "link")
        return ArbeidsgiversakCreate(
            orgnummer = orgnummer,
            recipient = recipient.toWireRecipient(),
            tag = tag,
            sakstilknytning = Sakstilknytning(caseId),
            title = title,
            link = link,
            initialStatus = initialStatus.toWireStatus(),
            hardDeleteAt = hardDeleteAt,
        ).encode(eventId, reference)
    }

    /**
     * Sets a new status on a case created with [arbeidsgiversakCreate]. Retrying the same dispatch
     * with the same [eventId] does not add a second status entry.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your own id for this dispatch, used for correlation and bookkeeping.
     * @param orgnummer the same organisation as in the create; the partition anchor.
     * @param tag the same merkelapp as in the create.
     * @param caseId the same case identity as in the create.
     * @param status the new status.
     * @param statusText optional text that replaces the default status text.
     * @param hardDeleteAt optional new deletion time for the case and its content; it replaces any
     *   earlier time, also an earlier one.
     */
    fun arbeidsgiversakStatus(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        tag: String,
        caseId: String,
        status: Arbeidsgiversak.Status,
        statusText: String? = null,
        hardDeleteAt: Instant? = null,
    ): EncodedDispatch {
        requireReference(reference)
        orgnummer.requireOrgnummer()
        requireNotBlank(tag, "tag")
        requireNotBlank(caseId, "caseId")
        requireNullOrNotBlank(statusText, "statusText")
        return ArbeidsgiversakStatus(
            orgnummer = orgnummer,
            tag = tag,
            sakstilknytning = Sakstilknytning(caseId),
            status = status.toWireStatus(),
            statusText = statusText,
            hardDeleteAt = hardDeleteAt,
        ).encode(eventId, reference)
    }

    /**
     * Creates a calendar event for the active Nærmeste leder in a case created with
     * [arbeidsgiversakCreate]. [reference] is the event's identity downstream: use it again in
     * [kalenderavtaleUpdate]. Start and end cannot be changed later; for a new time, cancel this
     * event and create a new one with a new [reference].
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference your stable, unique id for this calendar event.
     * @param orgnummer the organisation of the case; the partition anchor.
     * @param sykmeldt the Sykmeldt whose active Nærmeste leder receives the event. The leader must
     *   be the same as on the case.
     * @param tag the same merkelapp as on the case.
     * @param caseId the case identity from [arbeidsgiversakCreate].
     * @param text the event text shown to the leader.
     * @param link required target for the event.
     * @param startAt when the event starts.
     * @param endAt optional end; must be after [startAt].
     * @param location optional physical meeting place.
     * @param digital whether the leader can attend digitally.
     * @param state the initial state.
     * @param email optional email to the leader. It is sent during Arbeidsgivernotifikasjoner's
     *   contact-centre hours and is not sent if the event is cancelled first.
     */
    fun kalenderavtaleCreate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        sykmeldt: PersonIdentifier,
        tag: String,
        caseId: String,
        text: String,
        link: String,
        startAt: Instant,
        endAt: Instant? = null,
        location: Kalenderavtale.Location? = null,
        digital: Boolean = false,
        state: Kalenderavtale.State = Kalenderavtale.State.AWAITING_RESPONSE,
        email: Kalenderavtale.LeaderEmail? = null,
    ): EncodedDispatch {
        requireReference(reference)
        orgnummer.requireOrgnummer()
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(tag, "tag")
        requireNotBlank(caseId, "caseId")
        requireNotBlank(text, "text")
        requireNotBlank(link, "link")
        endAt?.let { require(it > startAt) { "endAt must be after startAt" } }
        return KalenderavtaleCreate(
            orgnummer = orgnummer,
            recipient = NarmesteLeder(sykmeldt = sykmeldt, externalVarsling = email?.toWireExternalVarsling()),
            tag = tag,
            sakstilknytning = Sakstilknytning(caseId),
            text = text,
            link = link,
            startAt = startAt,
            endAt = endAt,
            location = location?.toWireLocation(),
            digital = digital,
            state = state.toWireState(),
        ).encode(eventId, reference)
    }

    /**
     * Updates a calendar event created with [kalenderavtaleCreate]. Set at least one of [newState],
     * [newText] and [email].
     *
     * Known downstream limitation: Arbeidsgivernotifikasjoner does not deduplicate retried updates.
     * If a retry follows an update that already succeeded, an [email] can be sent twice.
     *
     * @param eventId unique per dispatch; reuse the same value when retrying the same dispatch.
     * @param reference the [kalenderavtaleCreate] reference of the event to update.
     * @param orgnummer the same organisation as in the create; the partition anchor.
     * @param sykmeldt the same Sykmeldt as in the create; used to resolve the leader's email.
     * @param tag the same merkelapp as in the create.
     * @param newState optional new state.
     * @param newText optional new text.
     * @param email optional email to the leader about the change.
     */
    fun kalenderavtaleUpdate(
        eventId: EventId,
        reference: String,
        orgnummer: Orgnummer,
        sykmeldt: PersonIdentifier,
        tag: String,
        newState: Kalenderavtale.State? = null,
        newText: String? = null,
        email: Kalenderavtale.LeaderEmail? = null,
    ): EncodedDispatch {
        requireReference(reference)
        orgnummer.requireOrgnummer()
        sykmeldt.requirePersonIdentifier("sykmeldt")
        requireNotBlank(tag, "tag")
        requireNullOrNotBlank(newText, "newText")
        require(newState != null || newText != null || email != null) {
            "newState, newText or email must be set"
        }
        return KalenderavtaleUpdate(
            orgnummer = orgnummer,
            recipient = NarmesteLeder(sykmeldt = sykmeldt, externalVarsling = email?.toWireExternalVarsling()),
            tag = tag,
            newState = newState?.toWireState(),
            newText = newText,
        ).encode(eventId, reference)
    }
}

private fun encodeDineSykmeldteVarselCreate(
    eventId: EventId,
    reference: String,
    sykmeldt: PersonIdentifier,
    orgnummer: Orgnummer,
    oppgavetype: Oppgavetype,
    text: String,
    visibleUntil: Instant?,
    sendingWindow: SendingWindow,
): EncodedDispatch {
    requireReference(reference)
    sykmeldt.requirePersonIdentifier("sykmeldt")
    orgnummer.requireOrgnummer()
    requireNotBlank(text, "text")
    return LedervarselCreate(
        sykmeldt = sykmeldt,
        orgnummer = orgnummer,
        oppgavetype = oppgavetype,
        text = text,
        visibleUntil = visibleUntil,
        sendingWindow = sendingWindow,
    ).encode(eventId, reference)
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

private fun encodeArbeidsgivervarsel(
    eventId: EventId,
    reference: String,
    orgnummer: Orgnummer,
    recipient: ArbeidsgiverRecipient,
    tag: String,
    text: String,
    link: String,
    messageType: Arbeidsgivervarsel.MessageType,
    caseAssociation: Arbeidsgivervarsel.CaseAssociation?,
    visibleUntil: Instant?,
    sendingWindow: SendingWindow,
): EncodedDispatch {
    requireReference(reference)
    orgnummer.requireOrgnummer()
    requireNotBlank(tag, "tag")
    requireNotBlank(text, "text")
    requireNotBlank(link, "link")
    caseAssociation?.let { requireNotBlank(it.caseId, "caseAssociation.caseId") }
    return ArbeidsgivervarselCreate(
        orgnummer = orgnummer,
        recipient = recipient,
        tag = tag,
        text = text,
        link = link,
        meldingstype = messageType.toWireMessageType(),
        sakstilknytning = caseAssociation?.let { Sakstilknytning(it.caseId) },
        visibleUntil = visibleUntil,
        sendingWindow = sendingWindow,
    ).encode(eventId, reference)
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

private fun Arbeidsgivervarsel.NarmesteLeder.toHtmlWireRecipient(htmlEmail: Arbeidsgivervarsel.HtmlEmailNotification): NarmesteLeder {
    require(externalNotification == null) {
        "recipient.externalNotification must be null when htmlEmail is set"
    }
    sykmeldt.requirePersonIdentifier("recipient.sykmeldt")
    requireNotBlank(htmlEmail.emailTitle, "htmlEmail.emailTitle")
    requireNotBlank(htmlEmail.emailHtmlBody, "htmlEmail.emailHtmlBody")
    return NarmesteLeder(
        sykmeldt = sykmeldt,
        externalVarsling =
            NarmesteLederExternalVarsling(
                emailTitle = htmlEmail.emailTitle,
                emailText = htmlEmail.emailHtmlBody,
                emailBodyFormat = EmailBodyFormat.HTML,
            ),
    )
}

private fun Arbeidsgivervarsel.AltinnResource.toHtmlWireRecipient(
    htmlEmail: Arbeidsgivervarsel.HtmlEmailNotification,
    smsText: String,
): AltinnResource {
    require(externalNotification == null) {
        "recipient.externalNotification must be null when htmlEmail is set"
    }
    requireNotBlank(resource, "recipient.resource")
    requireNotBlank(htmlEmail.emailTitle, "htmlEmail.emailTitle")
    requireNotBlank(htmlEmail.emailHtmlBody, "htmlEmail.emailHtmlBody")
    requireNotBlank(smsText, "smsText")
    return AltinnResource(
        resource = resource,
        externalVarsling =
            AltinnExternalVarsling(
                emailTitle = htmlEmail.emailTitle,
                emailText = htmlEmail.emailHtmlBody,
                smsText = smsText,
                emailBodyFormat = EmailBodyFormat.HTML,
            ),
    )
}

private fun Arbeidsgivervarsel.MessageType.toWireMessageType(): ArbeidsgiverMeldingstype =
    when (this) {
        Arbeidsgivervarsel.MessageType.BESKJED -> ArbeidsgiverMeldingstype.BESKJED
        Arbeidsgivervarsel.MessageType.OPPGAVE -> ArbeidsgiverMeldingstype.OPPGAVE
    }

/**
 * A scheduled hide is held in Budstikka's inbox, whose absolute retention cap is 365 days. The
 * 30-day margin keeps the hide well inside that cap; the same limit applies when Budstikka decides.
 */
private val MAX_MICROFRONTEND_VISIBILITY = 335.days

private fun requireVisibleUntilWithinLimit(visibleUntil: Instant) {
    val now = Clock.System.now()
    require(visibleUntil > now) { "visibleUntil must be in the future" }
    require(visibleUntil <= now + MAX_MICROFRONTEND_VISIBILITY) {
        "visibleUntil must be at most ${MAX_MICROFRONTEND_VISIBILITY.inWholeDays} days ahead"
    }
}

private fun Arbeidsgiversak.Recipient.toWireRecipient(): ArbeidsgiverRecipient =
    when (this) {
        is Arbeidsgiversak.NarmesteLeder -> {
            sykmeldt.requirePersonIdentifier("recipient.sykmeldt")
            NarmesteLeder(sykmeldt = sykmeldt)
        }
        is Arbeidsgiversak.AltinnResource ->
            AltinnResource(resource = resource.also { requireNotBlank(it, "recipient.resource") })
    }

private fun Arbeidsgiversak.Status.toWireStatus(): Saksstatus =
    when (this) {
        Arbeidsgiversak.Status.RECEIVED -> Saksstatus.MOTTATT
        Arbeidsgiversak.Status.IN_PROGRESS -> Saksstatus.UNDER_BEHANDLING
        Arbeidsgiversak.Status.DONE -> Saksstatus.FERDIG
    }

private fun Kalenderavtale.State.toWireState(): Kalenderavtaletilstand =
    when (this) {
        Kalenderavtale.State.AWAITING_RESPONSE -> Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER
        Kalenderavtale.State.EMPLOYER_WANTS_TO_CANCEL -> Kalenderavtaletilstand.ARBEIDSGIVER_VIL_AVLYSE
        Kalenderavtale.State.EMPLOYER_WANTS_TO_RESCHEDULE -> Kalenderavtaletilstand.ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED
        Kalenderavtale.State.EMPLOYER_ACCEPTED -> Kalenderavtaletilstand.ARBEIDSGIVER_HAR_GODTATT
        Kalenderavtale.State.CANCELLED -> Kalenderavtaletilstand.AVLYST
        Kalenderavtale.State.HELD -> Kalenderavtaletilstand.AVHOLDT
    }

private fun Kalenderavtale.Location.toWireLocation(): Kalenderavtalelokasjon {
    requireNotBlank(address, "location.address")
    requireNotBlank(postalCode, "location.postalCode")
    requireNotBlank(city, "location.city")
    return Kalenderavtalelokasjon(adresse = address, postnummer = postalCode, poststed = city)
}

private fun Kalenderavtale.LeaderEmail.toWireExternalVarsling(): NarmesteLederExternalVarsling {
    requireNotBlank(title, "email.title")
    requireNotBlank(body, "email.body")
    return NarmesteLederExternalVarsling(
        emailTitle = title,
        emailText = body,
        emailBodyFormat = if (isHtml) EmailBodyFormat.HTML else null,
    )
}
