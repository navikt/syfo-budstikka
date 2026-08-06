package no.nav.budstikka.domain.dispatch

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

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
    var sendingWindow: SendingWindow? = null,
) : DispatchContent,
    Brukervarsel {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.BUDSTIKKA_OPENING_HOURS
    }

    override val partitionKey: String get() = personIdentifier.value
}

/**
 * Activity notification in Dine Sykmeldte, partitioned by [sykmeldt]. External notification to the
 * leader is a separate [ArbeidsgivervarselCreate] with [NarmesteLeder] as recipient.
 */
@Serializable
@SerialName("LedervarselCreate")
data class LedervarselCreate(
    val sykmeldt: PersonIdentifier,
    val orgnummer: Orgnummer,
    val oppgavetype: Oppgavetype,
    val text: String,
    val link: String? = null,
    val visibleUntil: Instant? = null,
    var sendingWindow: SendingWindow? = null,
) : DispatchContent,
    Ledervarsel {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.ONGOING
    }

    override val partitionKey: String get() = sykmeldt.value
}

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

@Serializable
@SerialName("ArbeidsgivervarselCreate")
data class ArbeidsgivervarselCreate(
    val orgnummer: Orgnummer,
    @SerialName("mottaker")
    val recipient: ArbeidsgiverRecipient,
    val tag: Tag,
    val text: String,
    val link: String,
    val meldingstype: ArbeidsgiverMeldingstype = ArbeidsgiverMeldingstype.BESKJED,
    val sakstilknytning: Sakstilknytning? = null,
    val visibleUntil: Instant? = null,
    var sendingWindow: SendingWindow? = null,
) : DispatchContent {
    init {
        sendingWindow = sendingWindow ?: SendingWindow.BUDSTIKKA_OPENING_HOURS
    }

    override val partitionKey: String get() = orgnummer.value
}

/** Exactly one recipient path is selected for each Arbeidsgivervarsel. */
@Serializable
sealed interface ArbeidsgiverRecipient

@Serializable
@SerialName("NarmesteLeder")
data class NarmesteLeder(
    val sykmeldt: PersonIdentifier,
    val externalVarsling: NarmesteLederExternalVarsling? = null,
) : ArbeidsgiverRecipient

@Serializable
@SerialName("AltinnRessurs")
data class AltinnResource(
    val resource: AltinnResourceId,
    val externalVarsling: AltinnExternalVarsling? = null,
) : ArbeidsgiverRecipient

/** External notification texts required by the Altinn-resource delivery path. */
@Serializable
data class AltinnExternalVarsling(
    val emailTitle: String,
    val emailText: String,
    val smsText: String,
)

/** External notification texts required by the Nærmeste leder email delivery path. */
@Serializable
data class NarmesteLederExternalVarsling(
    val emailTitle: String,
    val emailText: String,
)

@Serializable
@SerialName("BrevCreate")
data class BrevCreate(
    val personIdentifier: PersonIdentifier,
    val journalpostId: String,
    val distributionType: DistributionType = DistributionType.IMPORTANT,
) : DispatchContent {
    override val partitionKey: String get() = personIdentifier.value
}
