package no.nav.budstikka.application.delivery

import no.nav.budstikka.contract.Kalenderavtalelokasjon
import no.nav.budstikka.contract.Kalenderavtaletilstand
import no.nav.budstikka.contract.Saksstatus
import kotlin.time.Instant

/**
 * Saker and kalenderavtaler in Arbeidsgivernotifikasjoner, addressed by their keys ([grupperingsid]
 * and eksternId) so budstikka stores no downstream ids.
 */
interface ArbeidsgiversakPublisher {
    /** Creates the sak, or succeeds when it already exists; [ArbeidsgiverNotificationResponse.SakDeleted] when the key is spent. */
    suspend fun createSak(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse

    /** Moves the sak's deletion to at least [ArbeidsgiversakExtendRequest.keepUntil]; not yet available when no sak has the key. */
    suspend fun extendSak(request: ArbeidsgiversakExtendRequest): ArbeidsgiverNotificationResponse

    /** Sets the saksstatus; retrying with the same idempotency key adds no second entry. */
    suspend fun setSaksstatus(request: ArbeidsgiversakStatusRequest): ArbeidsgiverNotificationResponse

    suspend fun createKalenderavtale(request: KalenderavtaleCreateRequest): ArbeidsgiverNotificationResponse

    suspend fun updateKalenderavtale(request: KalenderavtaleUpdateRequest): ArbeidsgiverNotificationResponse
}

data class ArbeidsgiversakCreateRequest(
    val virksomhetsnummer: String,
    val grupperingsid: String,
    val tag: String,
    val title: String,
    val link: String?,
    val keepUntil: Instant,
    val recipient: ArbeidsgiverNotificationRecipient,
    val correlationId: String,
) {
    override fun toString(): String = "ArbeidsgiversakCreateRequest(recipient=$recipient, hasLink=${link != null})"
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
    val text: String,
    val link: String,
    val startAt: Instant,
    val endAt: Instant?,
    val lokasjon: Kalenderavtalelokasjon?,
    val digital: Boolean,
    val tilstand: Kalenderavtaletilstand,
    val recipient: ArbeidsgiverNotificationRecipient.NarmesteLeder,
    val correlationId: String,
) {
    override fun toString(): String = "KalenderavtaleCreateRequest(tilstand=$tilstand, recipient=$recipient)"
}

data class KalenderavtaleUpdateRequest(
    val tag: String,
    val eksternId: String,
    val tilstand: Kalenderavtaletilstand?,
    val text: String?,
    val externalVarsling: NarmesteLederExternalVarsling?,
    val idempotencyKey: String,
) {
    override fun toString(): String = "KalenderavtaleUpdateRequest(tilstand=$tilstand, hasExternalVarsling=${externalVarsling != null})"
}
