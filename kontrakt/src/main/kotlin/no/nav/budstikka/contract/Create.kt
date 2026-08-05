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
    val externalVarsling: ExternalVarsling? = null,
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
    val tag: Tag,
    val text: String,
    val link: String,
    val externalVarsling: ExternalVarsling? = null,
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
        "ArbeidsgivervarselCreate(tag=$tag, meldingstype=$meldingstype, sendingWindow=$sendingWindow, " +
            "hasExternalVarsling=${externalVarsling != null}, hasSakstilknytning=${sakstilknytning != null})"
}

/** Exactly one recipient path is selected for each Arbeidsgivervarsel. */
@InternalBudstikkaWire
@Serializable
sealed interface ArbeidsgiverRecipient

/**
 * Personal Arbeidsgivervarsel path. No adapter delivers this variant yet; resolving Nærmeste leder
 * from [sykmeldt] is future work, not current behaviour.
 *
 * The generated `toString` is safe because [PersonIdentifier] masks itself.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
) : ArbeidsgiverRecipient

/** Everyone with the selected Altinn role at the organisation. */
@InternalBudstikkaWire
@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: AltinnResourceId,
) : ArbeidsgiverRecipient

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
