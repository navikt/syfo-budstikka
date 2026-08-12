package no.nav.budstikka.application.worker

internal class AlreadyLoggedWorkerFailure(
    cause: Throwable,
) : RuntimeException("Worker failure already logged", cause)
