package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakExtendRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import no.nav.budstikka.application.delivery.KalenderavtaleCreateRequest
import no.nav.budstikka.application.delivery.KalenderavtaleUpdateRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every case and calendar request. Queued responses are returned in order; when the queue is
 * empty, every request succeeds.
 */
class FakeArbeidsgiversakPublisher(
    vararg responses: ArbeidsgiverNotificationResponse,
) : ArbeidsgiversakPublisher {
    private val responses = ConcurrentLinkedQueue(responses.toList())
    private val recorded = CopyOnWriteArrayList<Any>()

    val requests: List<Any>
        get() = recorded.toList()

    val caseCreates: List<ArbeidsgiversakCreateRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakCreateRequest>()

    val caseExtensions: List<ArbeidsgiversakExtendRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakExtendRequest>()

    val caseStatuses: List<ArbeidsgiversakStatusRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakStatusRequest>()

    val calendarCreates: List<KalenderavtaleCreateRequest>
        get() = recorded.filterIsInstance<KalenderavtaleCreateRequest>()

    val calendarUpdates: List<KalenderavtaleUpdateRequest>
        get() = recorded.filterIsInstance<KalenderavtaleUpdateRequest>()

    override suspend fun createCase(request: ArbeidsgiversakCreateRequest) = record(request)

    override suspend fun extendCase(request: ArbeidsgiversakExtendRequest) = record(request)

    override suspend fun setCaseStatus(request: ArbeidsgiversakStatusRequest) = record(request)

    override suspend fun createCalendarEvent(request: KalenderavtaleCreateRequest) = record(request)

    override suspend fun updateCalendarEvent(request: KalenderavtaleUpdateRequest) = record(request)

    private fun record(request: Any): ArbeidsgiverNotificationResponse {
        recorded += request
        return responses.poll() ?: ArbeidsgiverNotificationResponse.Published
    }
}
