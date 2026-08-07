package no.nav.budstikka.application.port

import no.nav.budstikka.contract.AltinnResourceId
import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Tag

data class ArbeidsgiverNotificationRequest(
    val virksomhetsnummer: String,
    val eksternId: String,
    val grupperingsid: String?,
    val tag: Tag,
    val tekst: String,
    val lenke: String,
    val recipient: ArbeidsgiverNotificationRecipient,
    val meldingstype: ArbeidsgiverMeldingstype,
)

sealed interface ArbeidsgiverNotificationRecipient {
    data class AltinnRessurs(
        val resource: AltinnResourceId,
        val externalVarsling: AltinnExternalVarsling? = null,
    ) : ArbeidsgiverNotificationRecipient

    data class NarmesteLeder(
        val narmesteLederFnr: PersonIdentifier,
        val ansattFnr: PersonIdentifier,
        val externalVarsling: NarmesteLederExternalVarsling? = null,
    ) : ArbeidsgiverNotificationRecipient
}

data class AltinnExternalVarsling(
    val epostTittel: String,
    val epostTekst: String,
    val smsTekst: String,
)

data class NarmesteLederExternalVarsling(
    val epostTittel: String,
    val epostTekst: String,
    val epostadresser: List<String>,
)

sealed interface ArbeidsgiverNotificationResponse {
    data object Published : ArbeidsgiverNotificationResponse

    data class Rejected(
        val reason: String,
    ) : ArbeidsgiverNotificationResponse
}

interface ArbeidsgiverNotificationPublisher {
    suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse
}
