package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Close Brukervarsel; matching key = sykmeldt (CREATE partition anchor). */
@Serializable
@SerialName("BrukervarselInactivate")
data class BrukervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent,
    Brukervarsel {
    override val partitionKey: String get() = sykmeldt.value
}

/** Close Ledervarsel; Match key = Sykmeldt, NOT NL fnr (B24: unknown to the Produsent). */
@Serializable
@SerialName("LedervarselInactivate")
data class LedervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** Close Ditt Sykefravær message; Match key = Sykmeldt. */
@Serializable
@SerialName("DittSykefravaerInactivate")
data class DittSykefravaerInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** Close Arbeidsgivervarsel; matching key = organisation (B32). */
@Serializable
@SerialName("ArbeidsgivervarselInactivate")
data class ArbeidsgivervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val orgnummer: Orgnummer,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value
}

/**
 * Microfrontend (B41): visibility on Min side, kept OUTSIDE the Inactivate mechanism. It is a
 * separate activate/deactivate pair, an on/off switch for `(Sykmeldt, microfrontendId)`, not a
 * Delivery matched by `reference` and Recipient. A separate sealed subtype constrains the contract
 * to exactly this pair; the compiler enforces exhaustive `when` without `else`.
 */
@Serializable
sealed interface Microfrontend : DispatchContent {
    val personIdentifier: PersonIdentifier
    val microfrontendId: String
    override val partitionKey: String get() = personIdentifier.value
}

@Serializable
@SerialName("MicrofrontendEnable")
data class MicrofrontendEnable(
    override val personIdentifier: PersonIdentifier,
    override val microfrontendId: String,
    val visibleUntil: Instant? = null,
) : Microfrontend

/** Microfrontend “ferdigstill”: disable visibility (B41). */
@Serializable
@SerialName("MicrofrontendDisable")
data class MicrofrontendDisable(
    override val personIdentifier: PersonIdentifier,
    override val microfrontendId: String,
) : Microfrontend
