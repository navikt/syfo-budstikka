package no.nav.budstikka.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/*
 * Every Inactivate variant carries a `reference` and a Recipient and nothing else. `reference` is
 * the Produsent's own id for one notification to one person — an indirect identifier — so the
 * generated data class `toString` is replaced by the variant name alone. The variant is what a
 * log line needs; correlation belongs on the eventId.
 */

/** Close Brukervarsel; matching key = sykmeldt (CREATE partition anchor). */
@InternalBudstikkaWire
@Serializable
@SerialName("BrukervarselInactivate")
data class BrukervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent,
    Brukervarsel {
    override val partitionKey: String get() = sykmeldt.value

    /** Omits the reference and recipient. */
    override fun toString(): String = "BrukervarselInactivate()"
}

/** Close Ledervarsel; matching key = Sykmeldt (the CREATE partition anchor), never a leader's fnr. */
@InternalBudstikkaWire
@Serializable
@SerialName("LedervarselInactivate")
data class LedervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent,
    Ledervarsel {
    override val partitionKey: String get() = sykmeldt.value

    /** Omits the reference and recipient. */
    override fun toString(): String = "LedervarselInactivate()"
}

/** Close Ditt Sykefravær message; Match key = Sykmeldt. */
@InternalBudstikkaWire
@Serializable
@SerialName("DittSykefravaerInactivate")
data class DittSykefravaerInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value

    /** Omits the reference and recipient. */
    override fun toString(): String = "DittSykefravaerInactivate()"
}

/** Close Arbeidsgivervarsel; matching key = organisation. */
@InternalBudstikkaWire
@Serializable
@SerialName("ArbeidsgivervarselInactivate")
data class ArbeidsgivervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val orgnummer: Orgnummer,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value

    /** Omits the reference and recipient. */
    override fun toString(): String = "ArbeidsgivervarselInactivate()"
}

/**
 * Visibility toggle keyed by `(personIdentifier, microfrontendId)`, not by a Dispatch reference.
 * The sealed subtype constrains the contract to an enable/disable pair.
 */
@InternalBudstikkaWire
@Serializable
sealed interface Microfrontend : DispatchContent {
    val personIdentifier: PersonIdentifier
    val microfrontendId: String
    override val partitionKey: String get() = personIdentifier.value
}

@InternalBudstikkaWire
@Serializable
@SerialName("MicrofrontendEnable")
data class MicrofrontendEnable(
    override val personIdentifier: PersonIdentifier,
    override val microfrontendId: String,
    val visibleUntil: Instant? = null,
) : Microfrontend {
    /**
     * Omits [microfrontendId] because it names a surface shown to a person.
     */
    override fun toString(): String = "MicrofrontendEnable(hasVisibleUntil=${visibleUntil != null})"
}

/** Disable microfrontend visibility. */
@InternalBudstikkaWire
@Serializable
@SerialName("MicrofrontendDisable")
data class MicrofrontendDisable(
    override val personIdentifier: PersonIdentifier,
    override val microfrontendId: String,
) : Microfrontend {
    /** Omits the person identifier and microfrontend id. */
    override fun toString(): String = "MicrofrontendDisable()"
}
