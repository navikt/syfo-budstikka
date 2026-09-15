package no.nav.budstikka.infrastructure.worker

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object BackgroundLoopLogEvents {
    val shutdownTimedOut =
        Event<TimeoutContext>(
            name = "worker.shutdown.timed_out",
            level = ApplicationLogLevel.WARN,
            message = "Worker did not stop within timeout",
            operation = "worker.shutdown",
            errorCode = "WORKER_SHUTDOWN_TIMEOUT",
            fields = mapOf(MdcKeys.TIMEOUT_SECONDS to { it.timeoutSeconds }),
        )

    val iterationFailed =
        Event<FailureTypeContext>(
            name = "worker.iteration.failed",
            level = ApplicationLogLevel.ERROR,
            message = "Worker failed in iteration",
            operation = "worker.run_iteration",
            errorCode = "WORKER_ITERATION_FAILED",
            fields = mapOf(MdcKeys.ERROR_TYPE to { it.errorType }),
        )
}

internal data class TimeoutContext(
    val timeoutSeconds: Long,
)

internal data class FailureTypeContext(
    val errorType: String,
)
