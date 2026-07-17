package no.nav.budstikka.application

internal class AlreadyLoggedWorkerFailure(
    cause: Throwable,
) : RuntimeException("Worker failure already logged", cause)
