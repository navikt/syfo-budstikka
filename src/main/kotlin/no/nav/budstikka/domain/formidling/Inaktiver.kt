package no.nav.budstikka.domain.formidling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// FERDIGSTILL / lukking (B38, B39). Typet variant pr. lukkbar kanal – kanal er implisitt i
// typen og matchnøkkelen er typet (bevarer PII-maskering, B9). Hendelsen er THIN: kun
// `referanse` + typet nøkkel. Selve lukkeoperasjonen nedstrøms avledes fra den lagrede
// OPPRETT-raden (B39), aldri fra hendelsen. Matchnøkkel = OPPRETTs partisjonsanker.
// BREV er urepresenterbart (ingen `BrevInaktiver`, B3/B21).

/** Lukk brukervarsel; matchnøkkel = sykmeldt (OPPRETT-partisjonsanker). */
@Serializable
@SerialName("BrukervarselInaktiver")
data class BrukervarselInaktiver(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = sykmeldt.value
}

/** Lukk ledervarsel; matchnøkkel = sykmeldt, IKKE NL-fnr (B24 – ukjent for konsument). */
@Serializable
@SerialName("LedervarselInaktiver")
data class LedervarselInaktiver(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = sykmeldt.value
}

/** Lukk Ditt sykefravær-melding; matchnøkkel = sykmeldt. */
@Serializable
@SerialName("DittSykefravaerInaktiver")
data class DittSykefravaerInaktiver(
    val referanse: String,
    val sykmeldt: Personident,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = sykmeldt.value
}

/** Lukk arbeidsgivervarsel; matchnøkkel = virksomhet (B32). */
@Serializable
@SerialName("ArbeidsgivervarselInaktiver")
data class ArbeidsgivervarselInaktiver(
    val referanse: String,
    val orgnummer: Orgnummer,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = orgnummer.value
}

/**
 * Mikrofrontend (B41) – synlighet på Min side, holdt UTENFOR Inaktiver-mekanismen. Eget
 * aktiver/deaktiver-par: en av/på-bryter for `(person, mikrofrontendId)`, ikke en
 * leveranse-med-mottaker som matches på `referanse`. Egen sealed subtype så produsent-siden
 * tar imot nettopp dette paret – kompilatoren håndhever uttømmende `when` uten `else`.
 */
@Serializable
sealed interface Mikrofrontend : Formidlingsinnhold

@Serializable
@SerialName("MikrofrontendAktiver")
data class MikrofrontendAktiver(
    val personident: Personident,
    val mikrofrontendId: String,
    val synligTom: Instant? = null,
) : Mikrofrontend {
    override val partisjonsnokkel: String get() = personident.value
}

/** Mikrofrontendens «ferdigstill» – deaktiver synlighet (B41). */
@Serializable
@SerialName("MikrofrontendDeaktiver")
data class MikrofrontendDeaktiver(
    val personident: Personident,
    val mikrofrontendId: String,
) : Mikrofrontend {
    override val partisjonsnokkel: String get() = personident.value
}
