# Application logging

Production application code obtains an `ApplicationLogger` through the local
`applicationLogger(...)` binding in `application/logging/ApplicationLogging.kt`. That file is the
only production location allowed to create an SLF4J logger or call the eSyfo logger factory. Ktor
bootstrap logging uses the `Application.applicationLogger()` overload so the effective Ktor logger
name and test seam remain unchanged.

Use `info` and `debug` for diagnostics. Supply structured context as a map containing only primitive
values. Every `warn` or `error` outcome must instead be a named `Event` in
the owning subsystem, either beside its caller or in a small module-local `*LogEvents.kt` file.
Each event has a stable `event_type`, `operation`, `error_code`, level, and statically declared
context fields. Null context values are omitted from the encoded event; do not replace them with a
sentinel value. Only the logger factory is centralized.

## Reserved operation field

The eSyfo logger owns `operation` as a static lowercase identifier for the logical event operation.
This requires an intentional compatibility migration for poison-delivery logs: the former dynamic
`operation=CREATE|INACTIVATE` field is now `delivery_operation=CREATE|INACTIVATE`, while `operation`
is the static `delivery.fail_poison_row`. Existing poison-delivery log queries using `operation`
for the delivery action must move to `delivery_operation`. A repository search found no in-repo
dashboard or alert consuming the former field.

Log only operational identifiers and bounded code-owned values. Do not log payloads, national
identity numbers, notification text, tokens, raw downstream responses, or exception messages that
may contain sensitive input. Attach a cause only when the outcome's existing privacy policy permits
the stack trace. Otherwise expose a safe exception type or bounded reason without the throwable.
MDC remains responsible for cross-step correlation fields such as `event_id`, `reference`,
`consumer`, and `worker`.

When adding an event:

1. Define its name, operation, error code, level, message, and typed context in the owning module.
2. Add or update an owner-specific scenario test covering level, fields, event count, MDC and cause
   policy, and relevant privacy canaries.

These owner-specific scenario tests are the proof for each typed event. They capture Logback
directly through the existing test support.

`LoggingArchitectureTest` reads production Kotlin source and rejects direct `org.slf4j` or
`no.nav.esyfo.observability.createLogger` imports outside the local binding. It also rejects direct
standard-output calls. This deliberately narrow source-level guard is complemented by code review
and the owner-specific scenario tests.
