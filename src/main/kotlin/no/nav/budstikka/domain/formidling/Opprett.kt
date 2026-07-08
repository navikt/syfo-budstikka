package no.nav.budstikka.domain.formidling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// OPPRETT-varianter (B22) – rike, kanalspesifikke. Kanalen er implisitt i typen; ulovlige
// (kanal, felt)-kombinasjoner er urepresenterbare. Konsumenten eier *hva* og *når* (B1).

/** 1. Brukervarsel – sykmeldt, Min side. */
@Serializable
@SerialName("BrukervarselOpprett")
data class BrukervarselOpprett(
    val personident: Personident,
    val varseltype: Varseltype,
    val tekst: String,
    val lenke: String? = null,
    val synligTom: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val brevFallback: BrevFallback? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = personident.value
}

/**
 * 2. Ledervarsel – nærmeste leder, Dine Sykmeldte. Bærer `(sykmeldt, orgnummer)` – IKKE
 * NL-fnr; budstikka resolver nærmeste leder selv (B24). Partisjonsanker = sykmeldt.
 */
@Serializable
@SerialName("LedervarselOpprett")
data class LedervarselOpprett(
    val sykmeldt: Personident,
    val orgnummer: Orgnummer,
    val tekst: String,
    val lenke: String? = null,
    val synligTom: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = sykmeldt.value
}

/** 3. Ditt sykefravær-melding – sykmeldt. Ingen `variant`-felt (B40): nedstrøms har kun INFO. */
@Serializable
@SerialName("DittSykefravaerOpprett")
data class DittSykefravaerOpprett(
    val personident: Personident,
    val tekst: String,
    val lenke: String? = null,
    val synligTom: Instant? = null,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = personident.value
}

/** 4. Arbeidsgivervarsel – Min side arbeidsgiver / Altinn. */
@Serializable
@SerialName("ArbeidsgivervarselOpprett")
data class ArbeidsgivervarselOpprett(
    val orgnummer: Orgnummer,
    val mottaker: AgMottaker,
    val merkelapp: Merkelapp,
    val tekst: String,
    val lenke: String,
    val eksternVarsling: EksternVarsling? = null,
    val meldingstype: AgMeldingstype = AgMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val synligTom: Instant? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = orgnummer.value
}

/**
 * B32: de to mottaker-stiene kombineres ALDRI → sealed valg, ikke separate hendelsesvarianter.
 */
@Serializable
sealed interface AgMottaker

/** Personlig mottaker; budstikka resolver NL (B24) fra `(sykmeldt, orgnummer)`. */
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: Personident,
) : AgMottaker

/** Alle med Altinn-rollen ved virksomheten; `ressurs` typet (B30). */
@Serializable
@SerialName("AltinnRessurs")
data class AltinnRessurs(
    val ressurs: AltinnRessursId,
) : AgMottaker

/** 5. Brev – sykmeldt, fysisk. INGEN ferdigstill (B3/B21). */
@Serializable
@SerialName("BrevOpprett")
data class BrevOpprett(
    val personident: Personident,
    val journalpostId: String,
    val distribusjonstype: Distribusjonstype = Distribusjonstype.VIKTIG,
) : Formidlingsinnhold {
    override val partisjonsnokkel: String get() = personident.value
}
