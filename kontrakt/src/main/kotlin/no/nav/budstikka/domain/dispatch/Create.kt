package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Brukervarsel – sykmeldt. Eksponert på min side. */
@Serializable
@SerialName("BrukervarselCreate")
data class BrukervarselCreate(
    val personIdentifier: PersonIdentifier,
    val varseltype: Varseltype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val brevFallback: BrevFallback? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent,
    Brukervarsel {
    override val partitionKey: String get() = personIdentifier.value
}

/**
 * Ledervarsel – nærmeste leder. Eksponeres på **Dine Sykmeldte**. Bærer `(sykmeldt, orgnummer)`.
 * budstikka slår opp nærmeste leder selv. Partisjonsnøkkel = sykmeldt.
 */
@Serializable
@SerialName("LedervarselCreate")
data class LedervarselCreate(
    val sykmeldt: PersonIdentifier,
    val orgnummer: Orgnummer,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    val eksternVarsling: EksternVarsling? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** Ditt sykefravær-melding – sykmeldt. */
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

/** Arbeidsgivervarsel – Min side arbeidsgiver / Altinn. */
@Serializable
@SerialName("ArbeidsgivervarselCreate")
data class ArbeidsgivervarselCreate(
    val orgnummer: Orgnummer,
    val recipient: ArbeidsgiverRecipient,
    val merkelapp: Merkelapp,
    val text: String,
    val link: String,
    val eksternVarsling: EksternVarsling? = null,
    val meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val visibleUntil: Instant? = null,
    val sendingWindow: SendingWindow? = null,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value
}

@Serializable
sealed interface ArbeidsgiverRecipient

/** Personlig mottaker; budstikka slår opp nærmeste leder med `(sykmeldt, orgnummer)`. */
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
) : ArbeidsgiverRecipient

/** Alle med Altinn-rollen ved virksomheten; `ressurs` typet */
@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: AltinnResourceId,
) : ArbeidsgiverRecipient

/** Brev – sykmeldt, fysisk. INGEN ferdigstill */
@Serializable
@SerialName("BrevCreate")
data class BrevCreate(
    val personIdentifier: PersonIdentifier,
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}
