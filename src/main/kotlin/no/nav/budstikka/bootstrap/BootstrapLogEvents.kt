package no.nav.budstikka.bootstrap

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object BootstrapLogEvents {
    val replayConfigurationInvalid =
        Event<FailureTypeContext>(
            name = "dead_letter_replay.configuration.invalid",
            level = ApplicationLogLevel.ERROR,
            message = "Dead-letter replay configuration is invalid; replay skipped",
            operation = "dead_letter_replay.configure",
            errorCode = "REPLAY_CONFIGURATION_INVALID",
            fields = mapOf(MdcKeys.ERROR_TYPE to { it.errorType }),
        )

    val replayFailed =
        Event<FailureTypeContext>(
            name = "dead_letter_replay.failed",
            level = ApplicationLogLevel.ERROR,
            message = "Dead-letter replay failed",
            operation = "dead_letter_replay.replay",
            errorCode = "DEAD_LETTER_REPLAY_FAILED",
            fields = mapOf(MdcKeys.ERROR_TYPE to { it.errorType }),
        )

    val kafkaConsumerFatal =
        Event<Unit>(
            name = "kafka_consumer.bootstrap.fatal_failure",
            level = ApplicationLogLevel.ERROR,
            message = "Kafka consumer hit a fatal error; loop stopped, liveness will report stale",
            operation = "kafka_consumer.bootstrap",
            errorCode = "CONSUMER_BOOTSTRAP_FATAL",
        )
}

internal data class FailureTypeContext(
    val errorType: String,
)
