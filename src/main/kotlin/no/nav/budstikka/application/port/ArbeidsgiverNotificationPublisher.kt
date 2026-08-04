package no.nav.budstikka.application.port

import no.nav.budstikka.domain.dispatch.AltinnResourceId
import no.nav.budstikka.domain.dispatch.ArbeidsgiverMeldingstype
import no.nav.budstikka.domain.dispatch.Tag

data class ArbeidsgiverNotificationRequest(
    val virksomhetsnummer: String,
    val eksternId: String,
    val grupperingsid: String?,
    val tag: Tag,
    val tekst: String,
    val lenke: String,
    val altinnRessurs: AltinnResourceId,
    val meldingstype: ArbeidsgiverMeldingstype,
    val externalVarsling: ArbeidsgiverExternalVarsling? = null,
)

data class ArbeidsgiverExternalVarsling(
    val epostTittel: String,
    val epostTekst: String,
    val smsTekst: String,
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
