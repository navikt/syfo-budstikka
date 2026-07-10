package no.nav.budstikka.domain.formidling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// CREATE-varianter (B22) – rike, kanalspesifikke. Kanalen er implisitt i typen; ulovlige
// (kanal, felt)-kombinasjoner er urepresenterbare. Konsumenten eier *hva* og *når* (B1).

/** 1. Brukervarsel – sykmeldt, Min side. */
@Serializable
@SerialName("BrukervarselCreate")
data class BrukervarselCreate(
    val personident: Personident,
    val varseltype: Varseltype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val brevFallback: BrevFallback? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partitionKey: String get() = personident.value
}

/**
 * 2. Ledervarsel – nærmeste leder, Dine Sykmeldte. Bærer `(sykmeldt, orgnummer)` – IKKE
 * NL-fnr; budstikka resolver nærmeste leder selv (B24). Partisjonsanker = sykmeldt.
 */
@Serializable
@SerialName("LedervarselCreate")
data class LedervarselCreate(
    val sykmeldt: Personident,
    val orgnummer: Orgnummer,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partitionKey: String get() = sykmeldt.value
}

/** 3. Ditt sykefravær-melding – sykmeldt. Ingen `variant`-felt (B40): nedstrøms har kun INFO. */
@Serializable
@SerialName("DittSykefravaerCreate")
data class DittSykefravaerCreate(
    val personident: Personident,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
) : Formidlingsinnhold {
    override val partitionKey: String get() = personident.value
}

/** 4. Arbeidsgivervarsel – Min side arbeidsgiver / Altinn. */
@Serializable
@SerialName("ArbeidsgivervarselCreate")
data class ArbeidsgivervarselCreate(
    val orgnummer: Orgnummer,
    val mottaker: AgMottaker,
    val merkelapp: Merkelapp,
    val text: String,
    val link: String,
    val eksternVarsling: EksternVarsling? = null,
    val meldingstype: AgMeldingstype = AgMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val visibleUntil: Instant? = null,
    val sendevindu: Sendevindu? = null,
) : Formidlingsinnhold {
    override val partitionKey: String get() = orgnummer.value
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
@SerialName("BrevCreate")
data class BrevCreate(
    val personident: Personident,
    val journalpostId: String,
    val distribusjonstype: Distribusjonstype = Distribusjonstype.VIKTIG,
) : Formidlingsinnhold {
    override val partitionKey: String get() = personident.value
}
