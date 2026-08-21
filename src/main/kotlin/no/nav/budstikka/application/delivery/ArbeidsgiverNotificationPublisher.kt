package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.ArbeidsgiverMeldingstype
import no.nav.budstikka.contract.PersonIdentifier
import kotlin.time.Instant

data class ArbeidsgiverNotificationRequest(
    val virksomhetsnummer: String,
    val eksternId: String,
    val grupperingsid: String?,
    val tag: String,
    val tekst: String,
    val lenke: String,
    val recipient: ArbeidsgiverNotificationRecipient,
    val meldingstype: ArbeidsgiverMeldingstype,
    val visibleUntil: Instant?,
) {
    override fun toString(): String =
        "ArbeidsgiverNotificationRequest(meldingstype=$meldingstype, " +
            "hasGrupperingsid=${grupperingsid != null}, recipient=$recipient)"
}

sealed interface ArbeidsgiverNotificationRecipient {
    data class AltinnRessurs(
        val resource: String,
        val externalVarsling: AltinnExternalVarsling? = null,
    ) : ArbeidsgiverNotificationRecipient {
        override fun toString(): String = "AltinnRessurs(hasExternalVarsling=${externalVarsling != null})"
    }

    data class NarmesteLeder(
        val narmesteLederFnr: PersonIdentifier,
        val ansattFnr: PersonIdentifier,
        val externalVarsling: NarmesteLederExternalVarsling? = null,
    ) : ArbeidsgiverNotificationRecipient {
        override fun toString(): String = "NarmesteLeder(hasExternalVarsling=${externalVarsling != null})"
    }
}

data class AltinnExternalVarsling(
    val epostTittel: String,
    val epostTekst: String,
    val smsTekst: String,
) {
    override fun toString(): String = "AltinnExternalVarsling()"
}

data class NarmesteLederExternalVarsling(
    val epostTittel: String,
    val epostTekst: String,
    val epostadresser: List<String>,
) {
    override fun toString(): String = "NarmesteLederExternalVarsling()"
}

sealed interface ArbeidsgiverNotificationResponse {
    data object Published : ArbeidsgiverNotificationResponse

    data class Rejected(
        val reason: String,
    ) : ArbeidsgiverNotificationResponse
}

interface ArbeidsgiverNotificationPublisher {
    suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse
}
