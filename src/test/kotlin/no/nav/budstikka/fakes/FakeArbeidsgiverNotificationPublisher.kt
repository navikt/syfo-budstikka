package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.ArbeidsgiverNotificationCloseRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
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
