package no.nav.budstikka.domain.formidling

import kotlinx.serialization.Serializable

// Fellestyper for kontrakten (B23). Budstikka eier sin egen nøytrale modell og
// speiler ikke nedstrøms (tms/dokdist/notifikasjon-produsent-api). Intern mapping til
// nedstrøms er et anti-corruption-lag.

/** Brukervarsel-type på Min side (B40). tms støtter også `Innboks`, men den brukes aldri → utelatt. */
enum class Varseltype { BESKJED, OPPGAVE }

/** Ekstern varslingskanal (SMS/e-post) i tillegg til flaten. */
enum class EksternKanal { SMS, EMAIL }

/**
 * Vår egen modell for ekstern varsling (B23), mappes internt til tms. `null`-tekster =
 * NAV-standardtekst nedstrøms. Konsument oppgir ren tekst; budstikka saniterer (B29).
 */
@Serializable
data class EksternVarsling(
    val kanaler: Set<EksternKanal> = setOf(EksternKanal.SMS, EksternKanal.EMAIL),
    val smsText: String? = null,
    val emailTitle: String? = null,
    val emailText: String? = null,
)

/** Distribusjonstype for brev-utsending nedstrøms. */
enum class Distribusjonstype { VIKTIG, ANNET }

/**
 * B8: tilstedeværelse = send brev når mottakeren er reservert mot digital kontakt.
 * `journalpostId` er allerede opprettet av konsumenten.
 */
@Serializable
data class BrevFallback(
    val journalpostId: String,
    val distribusjonstype: Distribusjonstype = Distribusjonstype.VIKTIG,
)

/**
 * Sendevindu (B25) – nøytralt begrep, self-operasjonalisert i outbox. Default settes av
 * budstikka (NKS_AAPNINGSTID for eksternbærende, LOEPENDE ellers). Utvidbar.
 */
enum class Sendevindu { LOEPENDE, NKS_AAPNINGSTID }

/**
 * Merkelapp (B30) – typet LUKKET enum (kategori, ikke oppførsel). Budstikka forgrener
 * aldri på den; bæres kun til produsent-api. Lukket form tvinger fager-registrering og
 * budstikka-onboarding i synk. Utvides ved onboarding.
 */
enum class Merkelapp { DIALOGMOETE, OPPFOELGING }

/** B32: Altinn-ressurs → produsent-api ressursId (register-håndhevet). */
enum class AltinnRessursId { DIALOGMOETE, }

/** B33: nøytral AG-meldingstype, separat fra Brukervarsels [Varseltype]. */
enum class AgMeldingstype { BESKJED, OPPGAVE }

/** B31: konsumenten eier saken; `sakId` → grupperingsid nedstrøms. */
@Serializable
data class Sakstilknytning(
    val sakId: String,
)
