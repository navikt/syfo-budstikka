package no.nav.budstikka.application.delivery

import no.nav.budstikka.application.port.KeyedLock

/**
 * Creates one sak at a time per sak key, across replicas. Arbeidsgivernotifikasjoner checks for an
 * existing sak and then publishes the new one without a lock, so two concurrent creations of the
 * same new sak can both be published and leave the employer with a duplicate sak. Serialized, the
 * second creation finds the first sak and succeeds as a replay. Other operations are delegated
 * unchanged.
 */
class SerializedSakCreation(
    private val delegate: ArbeidsgiversakPublisher,
    private val lock: KeyedLock,
) : ArbeidsgiversakPublisher by delegate {
    override suspend fun createSak(request: ArbeidsgiversakCreateRequest): ArbeidsgiverNotificationResponse =
        lock.withLock(sakLockKey(request.tag, request.grupperingsid)) { delegate.createSak(request) }
}

/** Downstream sak identity: merkelapp and grupperingsid. */
internal fun sakLockKey(
    tag: String,
    grupperingsid: String,
): String = "ARBEIDSGIVERSAK\u0000$tag\u0000$grupperingsid"
