package no.nav.budstikka.fakes

import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationDeleteRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationPublisher
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationRequest
import no.nav.budstikka.application.delivery.ArbeidsgiverNotificationResponse
import java.util.concurrent.CopyOnWriteArrayList

class FakeArbeidsgiverNotificationPublisher : ArbeidsgiverNotificationPublisher {
    val deletedRequests = CopyOnWriteArrayList<ArbeidsgiverNotificationDeleteRequest>()

    override suspend fun delete(request: ArbeidsgiverNotificationDeleteRequest): ArbeidsgiverNotificationResponse {
        deletedRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }

    private val publishedRequests = CopyOnWriteArrayList<ArbeidsgiverNotificationRequest>()

    val requests: List<ArbeidsgiverNotificationRequest>
        get() = publishedRequests.toList()

    override suspend fun publish(request: ArbeidsgiverNotificationRequest): ArbeidsgiverNotificationResponse {
        publishedRequests += request
        return ArbeidsgiverNotificationResponse.Published
    }
}
