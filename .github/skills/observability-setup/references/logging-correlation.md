---
description: "Logging and correlation in syfo-budstikka: Nav-Call-Id, existing MDC keys, coroutine context, JSON logs, and tracing verification. Read when extending logging, correlation, or tracing."
---

# Logging and correlation

## Current state

- `api/Plugins.kt` reads/generates `Nav-Call-Id` using Ktor's `CallId`.
- `application/MdcLogging.kt` defines canonical MDC keys.
- Workers and consumers use `MDC.putCloseable` and `MDCContext` to carry
  fields through suspending work.
- `logback.xml` writes JSON to stdout.
- The repository currently has no `CallLogging` or explicit OpenTelemetry code.

## Choose correlation fields

- HTTP request: `Nav-Call-Id`.
- Intake and processing: `eventId`.
- create/inactivate across events: `reference`.
- Delivery: `channel`, `handler`, and relevant result fields.
- Technical loop: `worker` or `consumer`.

Keep a field in MDC only for the lifetime it identifies. Use
`MDC.putCloseable(...).use {}` for synchronous scope and `MDCContext()` when
coroutines suspend. Test both that the field is present on the correct log line
and that it is cleared afterwards.

## JSON contract

The minimum is timestamp, level, message, and logger. Domain data is added as
structured top-level fields. The Nais labels `app`, `namespace`, `cluster`, `pod`,
and `container` are not duplicated. Payloads, national identity numbers, tokens,
and secrets are never logged.

## Tracing

Before adding `trace_id` or `span_id` to logs:

1. Verify that Nais actually injects/exports OpenTelemetry in the environment.
2. Confirm an end-to-end trace in dev.
3. Add the fields to structured logs without replacing event/delivery correlation.
4. Document the query or Grafana link that uses them.

The absence of an active trace is an explicit result, not something the agent
fills in from a general NAV assumption.
