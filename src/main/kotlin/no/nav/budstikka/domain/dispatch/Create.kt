package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** 1. Brukervarsel: Sykmeldt, Min side. */
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
    val sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
) : DispatchContent,
    Brukervarsel {
    override val partitionKey: String get() = personIdentifier.value
}

/**
 * 2. Ledervarsel: nærmeste leder, Dine Sykmeldte. Carries `(sykmeldt, orgnummer)`, NOT an NL fnr;
 * a future channel adapter must resolve Nærmeste leder (B24). That lookup and handler are not
 * implemented yet. Partition anchor = Sykmeldt.
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
    val sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
) : DispatchContent {
    override val partitionKey: String get() = sykmeldt.value
}

/** 3. Ditt Sykefravær message: Sykmeldt. No `variant` field (B40): downstream has only INFO. */
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

/** 4. Arbeidsgivervarsel: Min side Arbeidsgiver / Altinn. */
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
    val sendingWindow: SendingWindow = SendingWindow.BUDSTIKKA_OPENING_HOURS,
) : DispatchContent {
    override val partitionKey: String get() = orgnummer.value
}

/**
 * B32: the two recipient paths are NEVER combined → sealed choice, not separate event variants.
 */
@Serializable
sealed interface ArbeidsgiverRecipient

/** Personal Arbeidsgivervarsel path; a future adapter must resolve Nærmeste leder (B24). */
@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
) : ArbeidsgiverRecipient

/** Everyone with the Altinn role at the organisation; typed `ressurs` (B30). */
@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: AltinnResourceId,
) : ArbeidsgiverRecipient

/** 5. Brev: physical, for a Sykmeldt. No Ferdigstill (B3/B21). */
@Serializable
@SerialName("BrevCreate")
data class BrevCreate(
    val personIdentifier: PersonIdentifier,
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}
