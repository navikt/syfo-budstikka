package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.Saksstatus
import kotlin.time.Instant

/**
 * Cases and calendar events in Arbeidsgivernotifikasjoner, addressed by their keys ([grupperingsid]
 * and eksternId) so budstikka stores no downstream ids.
 */
interface ArbeidsgiversakPublisher {
    /** Creates the case, or succeeds when it already exists; [ArbeidsgiverNotificationResponse.CaseDeleted] when the key is spent. */
    suspend fun createCase(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse

    /** Moves the case's deletion to at least [ArbeidsgiversakExtendRequest.keepUntil]; not yet available when no case has the key. */
    suspend fun extendCase(request: ArbeidsgiversakExtendRequest): ArbeidsgiverNotificationResponse

    /** Sets the case status; retrying with the same idempotency key adds no second entry. */
    suspend fun setCaseStatus(request: ArbeidsgiversakStatusRequest): ArbeidsgiverNotificationResponse

    suspend fun createCalendarEvent(request: KalenderavtaleCreateRequest): ArbeidsgiverNotificationResponse

    suspend fun updateCalendarEvent(request: KalenderavtaleUpdateRequest): ArbeidsgiverNotificationResponse
}

data class ArbeidsgiversakCreateRequest(
    val virksomhetsnummer: String,
    val grupperingsid: String,
    val tag: String,
    val tittel: String,
    val lenke: String?,
    val keepUntil: Instant,
    val recipient: ArbeidsgiverNotificationRecipient,
    val correlationId: String,
) {
    override fun toString(): String = "ArbeidsgiversakCreateRequest(recipient=$recipient, hasLenke=${lenke != null})"
}

data class ArbeidsgiversakExtendRequest(
    val grupperingsid: String,
    val tag: String,
    val keepUntil: Instant,
    val correlationId: String,
) {
    override fun toString(): String = "ArbeidsgiversakExtendRequest()"
}

data class ArbeidsgiversakStatusRequest(
    val grupperingsid: String,
    val tag: String,
    val status: Saksstatus,
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
    val correlationId: String,
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
