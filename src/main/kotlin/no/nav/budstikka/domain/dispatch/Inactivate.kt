package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Lukk brukervarsel; matchnøkkel = sykmeldt (CREATE-partisjonsanker). */
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

/** Lukk ledervarsel; matchnøkkel = sykmeldt, IKKE NL-fnr (B24 – ukjent for konsument). */
@Serializable
@SerialName("LedervarselInactivate")
data class LedervarselInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent,
    Ledervarsel {
    override val partitionKey: String get() = sykmeldt.value
}

/** Lukk Ditt sykefravær-melding; matchnøkkel = sykmeldt. */
@Serializable
@SerialName("DittSykefravaerInactivate")
data class DittSykefravaerInactivate(
    @SerialName("referanse")
    val reference: String,
    val sykmeldt: PersonIdentifier,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** Lukk arbeidsgivervarsel; matchnøkkel = virksomhet (B32). */
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
 * Microfrontend (B41) – synlighet på Min side, holdt UTENFOR Inaktiver-mekanismen. Eget
 * aktiver/deaktiver-par: en av/på-bryter for `(person, microfrontendId)`, ikke en
 * leveranse-med-mottaker som matches på `referanse`. Egen sealed subtype så produsent-siden
 * tar imot nettopp dette paret – kompilatoren håndhever uttømmende `when` uten `else`.
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

/** Microfrontendens «ferdigstill» – deaktiver synlighet (B41). */
@Serializable
@SerialName("MicrofrontendDisable")
data class MicrofrontendDisable(
    override val personIdentifier: PersonIdentifier,
    override val microfrontendId: String,
) : Microfrontend
