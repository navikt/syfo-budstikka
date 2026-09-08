package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import java.util.concurrent.CopyOnWriteArrayList

class FakeArbeidsgiverNotificationPublisher : ArbeidsgiverNotificationPublisher {
    private val publishedRequests = CopyOnWriteArrayList<ArbeidsgiverNotificationRequest>()
    private val closedRequests = CopyOnWriteArrayList<ArbeidsgiverNotificationCloseRequest>()

    val requests: List<ArbeidsgiverNotificationRequest>
        get() = publishedRequests.toList()

    val closeRequests: List<ArbeidsgiverNotificationCloseRequest>
        get() = closedRequests.toList()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        publishedRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }

    override suspend fun close(request: ArbeidsgiverNotificationCloseRequest): ArbeidsgiverNotificationResponse {
        closedRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }
}
