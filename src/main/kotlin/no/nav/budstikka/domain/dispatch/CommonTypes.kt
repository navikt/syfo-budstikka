package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.Serializable

// Fellestyper for kontrakten (B23). Budstikka eier sin egen nøytrale modell og
// speiler ikke nedstrøms (tms/dokdist/notifikasjon-produsent-api). Intern mapping til
// nedstrøms er et anti-corruption-lag.

/** Brukervarsel-type på Min side (B40). tms støtter også `Innboks`, men den brukes aldri → utelatt. */
enum class Varseltype { BESKJED, OPPGAVE }

sealed interface Brukervarsel {
    val partitionKey: String
}

/** Marker-grensesnitt for LEDERVARSEL-kanalens content (speiler [Brukervarsel]); dispatch-seam i handler. */
sealed interface Ledervarsel {
    val partitionKey: String
}

/**
 * Oppgavetype for LEDERVARSEL (B61, ADR 0008) – LUKKET, budstikka-eid enum (samme mønster som
 * [Tag]/[AltinnResourceId]), IKKE opak streng. Påkrevd av dinesykmeldte-kontrakten
 * (`OpprettHendelse.oppgavetype`, del av konsumentens PK `(id, oppgavetype)` + UI-gruppering).
 * Budstikka forgrener ALDRI på verdien (`when(oppgavetype)` finnes ikke, B30/B39) – enumen er en
 * navneliste. Kotlin-identifikatoren er frikoblet fra wiren via [wireValue]: dinesykmeldte kan
 * endre en wire-streng uten å røre produsentene. #108 leverer ÉN representativ verdi; resten
 * fylles additivt ved onboarding (B36).
 */
enum class Oppgavetype(
    val wireValue: String,
) {
    DIALOGMOTE_INNKALLING("DIALOGMOTE_INNKALLING"),
}

/** Ekstern varslingskanal (SMS/e-post) i tillegg til flaten. */
enum class ExternalChannel { SMS, EMAIL }

/**
 * Vår egen modell for ekstern varsling (B23), mappes internt til tms. `null`-tekster =
 * NAV-standardtekst nedstrøms. Konsument oppgir ren tekst; budstikka saniterer (B29).
 */
@Serializable
data class ExternalVarsling(
    val channels: Set<ExternalChannel> = setOf(ExternalChannel.SMS, ExternalChannel.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
)

/** Distribusjonstype for brev-utsending nedstrøms. */
enum class DistributionType { IMPORTANT, OTHER }

/**
 * B8: tilstedeværelse = send brev når mottakeren er reservert mot digital kontakt.
 * `journalpostId` er allerede opprettet av konsumenten.
 */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
)

/**
 * Sendevindu (B25) – nøytralt begrep, self-operasjonalisert i outbox. Default settes av
 * budstikka (NKS_AAPNINGSTID for eksternbærende, ONGOING ellers). Utvidbar.
 */
enum class SendingWindow { ONGOING, NKS_OPENING_HOURS }

/**
 * Merkelapp (B30) – typet LUKKET enum (kategori, ikke oppførsel). Budstikka forgrener
 * aldri på den; bæres kun til produsent-api. Lukket form tvinger fager-registrering og
 * budstikka-onboarding i synk. Utvides ved onboarding.
 */
enum class Tag { DIALOGMOETE, OPPFOELGING }

/** B32: Altinn-ressurs → produsent-api ressursId (register-håndhevet). */
enum class AltinnResourceId { DIALOGMOETE, }

/** B33: nøytral AG-meldingstype, separat fra Brukervarsels [Varseltype]. */
enum class ArbeidsgiverMeldingstype { BESKJED, OPPGAVE }

/** B31: konsumenten eier saken; `sakId` → grupperingsid nedstrøms. */
@Serializable
data class Sakstilknytning(
    val sakId: String,
)
