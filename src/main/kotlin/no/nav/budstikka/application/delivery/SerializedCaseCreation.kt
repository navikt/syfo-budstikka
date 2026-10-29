package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.KeyedLock

/**
 * Creates one case at a time per case key, across replicas. Arbeidsgivernotifikasjoner checks for an
 * existing case and then publishes the new one without a lock, so two concurrent creations of the
 * same new case can both be published and leave the employer with a duplicate case. Serialized, the
 * second creation finds the first case and succeeds as a replay. Other operations are delegated
 * unchanged.
 */
class SerializedCaseCreation(
    private val delegate: ArbeidsgiversakPublisher,
    private val lock: KeyedLock,
) : ArbeidsgiversakPublisher by delegate {
    override suspend fun createCase(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        lock.withLock(caseLockKey(request.tag, request.grupperingsid)) { delegate.createCase(request) }
}

/** Downstream case identity: merkelapp and grupperingsid. */
internal fun caseLockKey(
    tag: String,
    grupperingsid: String,
): String = "ARBEIDSGIVERSAK\u0000$tag\u0000$grupperingsid"
