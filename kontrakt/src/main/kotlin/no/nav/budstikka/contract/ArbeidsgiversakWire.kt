@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package no.nav.budstikka.contract

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * Calendar event variants for Arbeidsgivernotifikasjoner. Budstikka owns the case the content belongs
 * to; the producer only chooses its [Saktype]. All variants partition on the organisation, like
 * [ArbeidsgivervarselCreate].
 */

/** Budstikka-owned case for content in Arbeidsgivernotifikasjoner. */
@InternalBudstikkaWire
@Serializable
enum class Saktype { DIALOGMOTE, OPPFOLGINGSPLAN }

/** Case status a producer asks Budstikka to set, after the content. */
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
 * Creates a calendar event in the [saktype] case of the recipient's leader relation. The dispatch
 * reference is the event's downstream eksternId, so [KalenderavtaleUpdate] addresses it by the same
 * reference.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("KalenderavtaleCreate")
data class KalenderavtaleCreate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val saktype: Saktype,
    val text: String,
    val link: String,
    val startAt: Instant,
    val endAt: Instant? = null,
    val lokasjon: Kalenderavtalelokasjon? = null,
    val digital: Boolean = false,
    val tilstand: Kalenderavtaletilstand = Kalenderavtaletilstand.VENTER_SVAR_FRA_ARBEIDSGIVER,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val saksstatus: Saksstatus? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits free text, times and identifiers. */
    override fun toString(): String =
        "KalenderavtaleCreate(tilstand=$tilstand, hasEndAt=${endAt != null}, hasLokasjon=${lokasjon != null}, " +
            "digital=$digital)"
}

/**
 * Updates the calendar event whose eksternId is the dispatch reference; [saktype] gives its
 * merkelapp. [recipient] is needed only to resolve the leader's email address when an external
 * notification is requested.
 */
@InternalBudstikkaWire
@Serializable
@SerialName("KalenderavtaleUpdate")
data class KalenderavtaleUpdate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val saktype: Saktype,
    val tilstand: Kalenderavtaletilstand? = null,
    val text: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val saksstatus: Saksstatus? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits free text and identifiers. */
    override fun toString(): String = "KalenderavtaleUpdate(tilstand=$tilstand, hasText=${text != null})"
}
