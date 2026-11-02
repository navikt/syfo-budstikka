package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import no.nav.budstikka.application.delivery.ArbeidsgiversakCreateRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakExtendRequest
import no.nav.budstikka.application.delivery.ArbeidsgiversakPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiversakStatusRequest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records every sak request. Queued responses are returned in order; when the queue is empty, every
 * request succeeds.
 */
class FakeArbeidsgiversakPublisher(
    vararg responses: ArbeidsgiverNotificationResponse,
) : ArbeidsgiversakPublisher {
    private val responses = ConcurrentLinkedQueue(responses.toList())
    private val recorded = CopyOnWriteArrayList<Any>()

    val requests: List<Any>
        get() = recorded.toList()

    val sakCreates: List<ArbeidsgiversakCreateRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakCreateRequest>()

    val sakExtensions: List<ArbeidsgiversakExtendRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakExtendRequest>()

    val saksstatuser: List<ArbeidsgiversakStatusRequest>
        get() = recorded.filterIsInstance<ArbeidsgiversakStatusRequest>()

    override suspend fun createSak(request: ArbeidsgiversakCreateRequest) = record(request)

    override suspend fun extendSak(request: ArbeidsgiversakExtendRequest) = record(request)

    override suspend fun setSaksstatus(request: ArbeidsgiversakStatusRequest) = record(request)

    private fun record(request: Any): ArbeidsgiverNotificationResponse {
        recorded += request
        return responses.poll() ?: ArbeidsgiverNotificationResponse.Published
    }
}
