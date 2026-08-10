package no.nav.budstikka.fakes

import no.nav.budstikka.application.port.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.port.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.port.ArbeidsgiverNotificationResponse
import java.util.concurrent.CopyOnWriteArrayList

class FakeArbeidsgiverNotificationPublisher : ArbeidsgiverNotificationPublisher {
    private val publishedRequests = CopyOnWriteArrayList<ArbeidsgiverNotificationRequest>()

    val requests: List<ArbeidsgiverNotificationRequest>
        get() = publishedRequests.toList()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        publishedRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }
}
