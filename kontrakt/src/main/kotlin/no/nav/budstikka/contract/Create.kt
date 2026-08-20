package no.nav.budstikka.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@InternalBudstikkaWire
@Serializable
@SerialName("BrukervarselCreate")
data class BrukervarselCreate(
    val personIdentifier: PersonIdentifier,
    val varseltype: Varseltype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val externalVarsling: ExternalNotification? = null,
    val brevFallback: BrevFallback? = null,
    var sendingWindow: SendingWindow? = null,
) : DispatchContent,
    Brukervarsel {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.BUDSTIKKA_OPENING_HOURS
    }

    override val partitionKey: String get() = personIdentifier.value

    /**
     * Omits free text and identifiers. Only technical, non-identifying values are printed.
     */
    override fun toString(): String =
        "BrukervarselCreate(varseltype=$varseltype, sendingWindow=$sendingWindow, " +
            "hasLink=${link != null}, hasExternalVarsling=${externalVarsling != null}, " +
            "hasBrevFallback=${brevFallback != null})"
}

/**
 * In-app activity notification in Dine Sykmeldte, partitioned by [sykmeldt]. Budstikka does not
 * resolve a leader for this variant; external notification to a leader is a separate
 * [ArbeidsgivervarselCreate] with [NarmesteLeder] as recipient.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("LedervarselCreate")
data class LedervarselCreate(
    val sykmeldt: PersonIdentifier,
    val orgnummer: Orgnummer,
    val oppgavetype: Oppgavetype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    var sendingWindow: SendingWindow? = null,
) : DispatchContent,
    Ledervarsel {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.ONGOING
    }

    override val partitionKey: String get() = sykmeldt.value

    /** Omits free text and identifiers; see [BrukervarselCreate.toString]. */
    override fun toString(): String = "LedervarselCreate(oppgavetype=$oppgavetype, sendingWindow=$sendingWindow, hasLink=${link != null})"
}

/** Ditt Sykefravær notification. The downstream contract has only the INFO variant. */
@InternalBudstikkaWire
@Serializable
@SerialName("DittSykefravaerCreate")
data class DittSykefravaerCreate(
    val personIdentifier: PersonIdentifier,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value

    /** Omits free text and identifiers; see [BrukervarselCreate.toString]. */
    override fun toString(): String = "DittSykefravaerCreate(hasLink=${link != null})"
}

/** Notification for Min side Arbeidsgiver or Altinn. */
@InternalBudstikkaWire
@Serializable
@SerialName("ArbeidsgivervarselCreate")
data class ArbeidsgivervarselCreate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: String,
    val text: String,
    val link: String,
    val meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val visibleUntil: Instant? = null,
    var sendingWindow: SendingWindow? = null,
) : DispatchContent {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.BUDSTIKKA_OPENING_HOURS
    }

    override val partitionKey: String get() = orgnummer.value

    /** Omits free text and identifiers; see [BrukervarselCreate.toString]. */
    override fun toString(): String =
        "ArbeidsgivervarselCreate(meldingstype=$meldingstype, sendingWindow=$sendingWindow, " +
            "hasExternalVarsling=${recipient.hasExternalVarsling()}, hasSakstilknytning=${sakstilknytning != null})"
}

/** Exactly one recipient path is selected for each Arbeidsgivervarsel. */
@InternalBudstikkaWire
@Serializable
sealed interface ArbeidsgiverRecipient

/**
 * Personal Arbeidsgivervarsel path. Budstikka resolves the active Nærmeste leder from [sykmeldt]
 * at delivery time.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
    val externalVarsling: NarmesteLederExternalVarsling? = null,
) : ArbeidsgiverRecipient {
    /** Omits the person identifier and external notification text. */
    override fun toString(): String = "NarmesteLeder(hasExternalVarsling=${externalVarsling != null})"
}

/** Everyone with the selected Altinn role at the organisation. */
@InternalBudstikkaWire
@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: String,
    val externalVarsling: AltinnExternalVarsling? = null,
) : ArbeidsgiverRecipient {
    /** Omits external notification text. */
    override fun toString(): String = "AltinnResource(hasExternalVarsling=${externalVarsling != null})"
}

/** External notification texts required by the Altinn-resource delivery path. */
@InternalBudstikkaWire
@Serializable
data class AltinnExternalVarsling(
    val emailTitle: String,
    val emailText: String,
    val smsText: String,
) {
    /** Omits all notification text. */
    override fun toString(): String = "AltinnExternalVarsling()"
}

/** External notification texts required by the Nærmeste leder email delivery path. */
@InternalBudstikkaWire
@Serializable
data class NarmesteLederExternalVarsling(
    val emailTitle: String,
    val emailText: String,
) {
    /** Omits all notification text. */
    override fun toString(): String = "NarmesteLederExternalVarsling()"
}

@OptIn(InternalBudstikkaWire::class)
private fun ArbeidsgiverRecipient.hasExternalVarsling(): Boolean =
    when (this) {
        is AltinnResource -> externalVarsling != null
        is NarmesteLeder -> externalVarsling != null
    }

/**
 * A document distributed to the Sykmeldt through dokumentdistribusjon. By default dokdist picks
 * the channel itself (digital mailbox for persons without Reservasjon, print otherwise);
 * [tvingSentralPrint] forces paper. This variant has no inactivation operation.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("BrevCreate")
data class BrevCreate(
    val personIdentifier: PersonIdentifier,
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
    val tvingSentralPrint: Boolean = false,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value

    /** Omits [journalpostId] because it identifies a document about a person. */
    override fun toString(): String = "BrevCreate(distributionType=$distributionType, tvingSentralPrint=$tvingSentralPrint)"
}
