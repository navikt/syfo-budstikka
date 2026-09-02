package no.nav.budstikka.application.inbox

enum class DeadLetterReason(
    val code: String,
    val metricTag: String,
    val safeMessage: String,
) {
    MISSING_PAYLOAD(
        code = "MISSING_PAYLOAD",
        metricTag = "missing_payload",
        safeMessage = "Kafka record missing payload",
    ),
    MISSING_EVENT_ID(
        code = "MISSING_EVENT_ID",
        metricTag = "missing_event_id",
        safeMessage = "Kafka record missing event ID header",
    ),
    INVALID_EVENT_ID(
        code = "INVALID_EVENT_ID",
        metricTag = "invalid_event_id",
        safeMessage = "Kafka event ID header is not a valid UUID",
    ),
    UNPARSEABLE_PAYLOAD(
        code = "UNPARSEABLE_PAYLOAD",
        metricTag = "unparseable_payload",
        safeMessage = "Kafka record payload could not be parsed as a Dispatch",
    ),
}
