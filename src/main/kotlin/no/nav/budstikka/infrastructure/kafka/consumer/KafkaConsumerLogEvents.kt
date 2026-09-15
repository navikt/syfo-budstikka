package no.nav.budstikka.infrastructure.kafka.consumer

import no.nav.budstikka.application.logging.ApplicationLogLevel
import no.nav.budstikka.application.logging.MdcKeys
import no.nav.esyfo.observability.Event

internal object KafkaConsumerLogEvents {
    val shutdownTimedOut =
        Event<TimeoutContext>(
            name = "kafka_consumer.shutdown.timed_out",
            level = ApplicationLogLevel.WARN,
            message = "Consumer did not stop within timeout",
            operation = "kafka_consumer.shutdown",
            errorCode = "CONSUMER_SHUTDOWN_TIMEOUT",
            fields = mapOf(MdcKeys.TIMEOUT_SECONDS to { it.timeoutSeconds }),
        )

    val fatalFailure =
        Event<Unit>(
            name = "kafka_consumer.fatal_failure",
            level = ApplicationLogLevel.ERROR,
            message = "Consumer hit a fatal error and will not restart",
            operation = "kafka_consumer.poll",
            errorCode = "CONSUMER_FATAL_FAILURE",
        )

    val restart =
        Event<BackoffContext>(
            name = "kafka_consumer.restart",
            level = ApplicationLogLevel.WARN,
            message = "Consumer failed, restarting after backoff",
            operation = "kafka_consumer.poll",
            errorCode = "CONSUMER_TRANSIENT_FAILURE",
            fields = mapOf(MdcKeys.BACKOFF_MILLIS to { it.backoffMillis }),
        )

    val poisonInboxDeadLettered =
        Event<DeadLetterContext>(
            name = "inbox.poison_message.dead_lettered",
            level = ApplicationLogLevel.WARN,
            message = "Poison inbox message dead-lettered",
            operation = "inbox.dead_letter",
            errorCodeFrom = { it.reason },
            fields =
                mapOf(
                    MdcKeys.REASON to { it.reason },
                    MdcKeys.TOPIC to { it.topic },
                    MdcKeys.PARTITION to { it.partition },
                    MdcKeys.KAFKA_OFFSET to { it.kafkaOffset },
                ),
        )
}

internal data class TimeoutContext(
    val timeoutSeconds: Long,
)

internal data class BackoffContext(
    val backoffMillis: Long,
)

internal data class DeadLetterContext(
    val reason: String,
    val topic: String,
    val partition: Int,
    val kafkaOffset: Long,
)
