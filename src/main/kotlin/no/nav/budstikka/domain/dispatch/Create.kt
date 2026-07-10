package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

// CREATE-varianter (B22) – rike, kanalspesifikke. Channelen er implisitt i typen; ulovlige
// (kanal, felt)-kombinasjoner er urepresenterbare. Konsumenten eier *hva* og *når* (B1).

/** 1. Brukervarsel – sykmeldt, Min side. */
@Serializable
@SerialName("BrukervarselCreate")
data class BrukervarselCreate(
    val personIdentifier: PersonIdentifier,
    val varseltype: Varseltype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val externalVarsling: ExternalVarsling? = null,
    val brevFallback: BrevFallback? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}

/**
 * 2. Ledervarsel – nærmeste leder, Dine Sykmeldte. Bærer `(sykmeldt, orgnummer)` – IKKE
 * NL-fnr; budstikka resolver nærmeste leder selv (B24). Partisjonsanker = sykmeldt.
 */
@Serializable
@SerialName("LedervarselCreate")
data class LedervarselCreate(
    val sykmeldt: PersonIdentifier,
    val orgnummer: Orgnummer,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val externalVarsling: ExternalVarsling? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** 3. Ditt sykefravær-melding – sykmeldt. Ingen `variant`-felt (B40): nedstrøms har kun INFO. */
@Serializable
@SerialName("DittSykefravaerCreate")
data class DittSykefravaerCreate(
    val personIdentifier: PersonIdentifier,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}

/** 4. Arbeidsgivervarsel – Min side arbeidsgiver / Altinn. */
@Serializable
@SerialName("ArbeidsgivervarselCreate")
data class ArbeidsgivervarselCreate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: Tag,
    val text: String,
    val link: String,
    val externalVarsling: ExternalVarsling? = null,
    val meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val visibleUntil: Instant? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value
}

/**
 * B32: de to mottaker-stiene kombineres ALDRI → sealed valg, ikke separate hendelsesvarianter.
 */
@Serializable
sealed interface ArbeidsgiverRecipient

/** Personlig mottaker; budstikka resolver NL (B24) fra `(sykmeldt, orgnummer)`. */
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
) : ArbeidsgiverRecipient

/** Alle med Altinn-rollen ved virksomheten; `ressurs` typet (B30). */
@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: AltinnResourceId,
) : ArbeidsgiverRecipient

/** 5. Brev – sykmeldt, fysisk. INGEN ferdigstill (B3/B21). */
@Serializable
@SerialName("BrevCreate")
data class BrevCreate(
    val personIdentifier: PersonIdentifier,
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}
