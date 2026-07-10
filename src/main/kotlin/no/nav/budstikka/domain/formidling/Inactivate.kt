package no.nav.budstikka.domain.formidling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// FERDIGSTILL / lukking (B38, B39). Typet variant pr. lukkbar kanal – kanal er implisitt i
// typen og matchnøkkelen er typet (bevarer PII-maskering, B9). Hendelsen er THIN: kun
// `referanse` + typet nøkkel. Selve lukkeoperasjonen nedstrøms avledes fra den lagrede
// CREATE-raden (B39), aldri fra hendelsen. Matchnøkkel = OPPRETTs partisjonsanker.
// BREV er urepresenterbart (ingen `BrevInaktiver`, B3/B21).

/** Lukk brukervarsel; matchnøkkel = sykmeldt (CREATE-partisjonsanker). */
@Serializable
@SerialName("BrukervarselInactivate")
data class BrukervarselInactivate(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partitionKey: String get() = sykmeldt.value
}

/** Lukk ledervarsel; matchnøkkel = sykmeldt, IKKE NL-fnr (B24 – ukjent for konsument). */
@Serializable
@SerialName("LedervarselInactivate")
data class LedervarselInactivate(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partitionKey: String get() = sykmeldt.value
}

/** Lukk Ditt sykefravær-melding; matchnøkkel = sykmeldt. */
@Serializable
@SerialName("DittSykefravaerInactivate")
data class DittSykefravaerInactivate(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partitionKey: String get() = sykmeldt.value
}

/** Lukk arbeidsgivervarsel; matchnøkkel = virksomhet (B32). */
@Serializable
@SerialName("ArbeidsgivervarselInactivate")
data class ArbeidsgivervarselInactivate(
    val referanse: String,
    val orgnummer: Orgnummer,
) : Formidlingsinnhold {
    override val partitionKey: String get() = orgnummer.value
}

/**
 * Mikrofrontend (B41) – synlighet på Min side, holdt UTENFOR Inaktiver-mekanismen. Eget
 * aktiver/deaktiver-par: en av/på-bryter for `(person, mikrofrontendId)`, ikke en
 * leveranse-med-mottaker som matches på `referanse`. Egen sealed subtype så produsent-siden
 * tar imot nettopp dette paret – kompilatoren håndhever uttømmende `when` uten `else`.
 */
@Serializable
sealed interface Mikrofrontend : Formidlingsinnhold {
    val personident: Personident
    val mikrofrontendId: String
    override val partitionKey: String get() = personident.value
}

@Serializable
@SerialName("MikrofrontendEnable")
data class MikrofrontendEnable(
    override val personident: Personident,
    override val mikrofrontendId: String,
    val visibleUntil: Instant? = null,
) : Mikrofrontend

/** Mikrofrontendens «ferdigstill» – deaktiver synlighet (B41). */
@Serializable
@SerialName("MikrofrontendDisable")
data class MikrofrontendDisable(
    override val personident: Personident,
    override val mikrofrontendId: String,
) : Mikrofrontend
