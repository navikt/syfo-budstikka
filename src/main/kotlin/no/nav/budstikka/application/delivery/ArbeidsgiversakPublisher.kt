package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.Saksstatus
import kotlin.time.Instant

/**
 * Producer-owned case and calendar objects in Arbeidsgivernotifikasjoner, addressed by the producer's
 * own keys ([grupperingsid] and eksternId) so budstikka stores no downstream ids.
 */
interface ArbeidsgiversakPublisher {
    suspend fun createCase(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse

    suspend fun updateCaseStatus(request: ArbeidsgiversakStatusRequest): ArbeidsgiverNotificationResponse

    suspend fun createCalendarEvent(request: KalenderavtaleCreateRequest): ArbeidsgiverNotificationResponse

    suspend fun updateCalendarEvent(request: KalenderavtaleUpdateRequest): ArbeidsgiverNotificationResponse
}

data class ArbeidsgiversakCreateRequest(
    val virksomhetsnummer: String,
    val grupperingsid: String,
    val tag: String,
    val tittel: String,
    val lenke: String?,
    val initiellStatus: Saksstatus,
    val hardDeleteAt: Instant?,
    val recipient: ArbeidsgiverNotificationRecipient,
    val correlationId: String,
) {
    override fun toString(): String = "ArbeidsgiversakCreateRequest(initiellStatus=$initiellStatus, recipient=$recipient)"
}

data class ArbeidsgiversakStatusRequest(
    val grupperingsid: String,
    val tag: String,
    val status: Saksstatus,
    val statustekst: String?,
    val hardDeleteAt: Instant?,
    val idempotencyKey: String,
) {
    override fun toString(): String = "ArbeidsgiversakStatusRequest(status=$status)"
}

data class KalenderavtaleCreateRequest(
    val virksomhetsnummer: String,
    val grupperingsid: String,
    val tag: String,
    val eksternId: String,
    val tekst: String,
    val lenke: String,
    val startAt: Instant,
    val endAt: Instant?,
    val lokasjon: Kalenderavtalelokasjon?,
    val erDigitalt: Boolean,
    val tilstand: Kalenderavtaletilstand,
    val recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
) {
    override fun toString(): String = "KalenderavtaleCreateRequest(tilstand=$tilstand, recipient=$recipient)"
}

data class KalenderavtaleUpdateRequest(
    val tag: String,
    val eksternId: String,
    val nyTilstand: Kalenderavtaletilstand?,
    val nyTekst: String?,
    val externalVarsling: NarmesteLederExternalVarsling?,
    val idempotencyKey: String,
) {
    override fun toString(): String = "KalenderavtaleUpdateRequest(nyTilstand=$nyTilstand, hasExternalVarsling=${externalVarsling != null})"
}
