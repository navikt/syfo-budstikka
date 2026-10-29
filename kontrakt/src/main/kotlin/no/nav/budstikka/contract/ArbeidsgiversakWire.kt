package no.nav.budstikka.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * Case and calendar event variants for Arbeidsgivernotifikasjoner. The producer owns the case
 * identity ([Sakstilknytning.sakId]) and every state transition; budstikka makes sure the case exists
 * before content is created and keeps no case state of its own. All variants partition on the
 * organisation, like [ArbeidsgivervarselCreate].
 */

/** Case status in Arbeidsgivernotifikasjoner. */
@InternalBudstikkaWire
@Serializable
enum class Saksstatus { MOTTATT, UNDER_BEHANDLING, FERDIG }

/** Calendar event state in Arbeidsgivernotifikasjoner. */
@InternalBudstikkaWire
@Serializable
enum class Kalenderavtaletilstand {
    VENTER_SVAR_FRA_ARBEIDSGIVER,
    ARBEIDSGIVER_VIL_AVLYSE,
    ARBEIDSGIVER_VIL_ENDRE_TID_ELLER_STED,
    ARBEIDSGIVER_HAR_GODTATT,
    AVLYST,
    AVHOLDT,
}

/** Physical meeting place shown on a calendar event. */
@InternalBudstikkaWire
@Serializable
data class Kalenderavtalelokasjon(
    val adresse: String,
    val postnummer: String,
    val poststed: String,
) {
    /** Omits the address. */
    override fun toString(): String = "Kalenderavtalelokasjon()"
}

/**
 * Sets a new status on the case described by [sakstilknytning], after making sure it exists for
 * [recipient]. A later [Sakstilknytning.hardDeleteAt] replaces the case's earlier deletion time.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("ArbeidsgiversakStatus")
data class ArbeidsgiversakStatus(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: String,
    val sakstilknytning: Sakstilknytning,
    val status: Saksstatus,
    val statusText: String? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits free text and identifiers. */
    override fun toString(): String = "ArbeidsgiversakStatus(status=$status, hasStatusText=${statusText != null})"
}

/**
 * Creates a calendar event in the case described by [sakstilknytning], after making sure the case
 * exists. The dispatch reference is the event's downstream eksternId, so [KalenderavtaleUpdate]
 * addresses it by the same reference.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("KalenderavtaleCreate")
data class KalenderavtaleCreate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: String,
    val sakstilknytning: Sakstilknytning,
    val text: String,
    val link: String,
    val startAt: Instant,
    val endAt: Instant? = null,
    val lokasjon: Kalenderavtalelokasjon? = null,
    val digital: Boolean = false,
    val tilstand: Kalenderavtaletilstand = Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits free text, times and identifiers. */
    override fun toString(): String =
        "KalenderavtaleCreate(tilstand=$tilstand, hasEndAt=${endAt != null}, hasLokasjon=${lokasjon != null}, " +
            "digital=$digital)"
}

/**
 * Updates the calendar event whose eksternId is the dispatch reference. [recipient] is needed only
 * to resolve the leader's email address when an external notification is requested.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("KalenderavtaleUpdate")
data class KalenderavtaleUpdate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: String,
    val tilstand: Kalenderavtaletilstand? = null,
    val text: String? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits free text and identifiers. */
    override fun toString(): String = "KalenderavtaleUpdate(tilstand=$tilstand, hasText=${text != null})"
}
