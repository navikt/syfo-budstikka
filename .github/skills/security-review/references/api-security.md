# API security — NAV signal

Generic API security (CORS setup, CSP/X-Frame-Options/HSTS, Ktor `Authentication`/`CORS` plugin boilerplate, rate-limit filter code, cookie `Secure/HttpOnly/SameSite`, session fixation, CSRF theory) is out of scope — the LLM knows this, and the auth setup in the code is covered by `/auth-overview`. This reference covers only the NAV-specific signal.

## Traceability with Nav-Call-Id

`Nav-Call-Id` must be propagated through the entire chain. In this repository the `CallId` plugin (`api/Plugins.kt`) reads `Nav-Call-Id` from the incoming request, generates a UUID when it is absent, and echoes it in the response header. `callIdMdc` is deliberately not installed, so a callId does not reach MDC or the log lines by itself; worker and consumer logs correlate through `MdcKeys` + `MDCContext` instead (see `/kotlin-ktor`).

In client calls to other NAV services: set `Nav-Call-Id` explicitly on the request and reuse the id you already have (`DocumentDistributionClient` sends the event id), do not generate a new one per hop. The header is used for correlation, audit and troubleshooting across services. It is not tied to `accessPolicy`, which is NAIS's network control, and it is *never* used as a basis for authorization.

## Nav-Consumer-Id for rate limiting and audit

For rate limiting against internal consumers: use `Nav-Consumer-Id` as the key before you fall back on IP. That gives meaningful limits per consumer app, not per NAIS pod.

## accessPolicy is the primary mechanism

CORS, IP allowlisting and self-validation of `Origin` are secondary. The primary network defense on the NAIS platform is `accessPolicy.inbound/outbound`. See the SKILL.md section "accessPolicy as first-line defense".

If a frontend-facing surface with Wonderwall in front is ever added (this repository has no ingress today): CSRF protection and cookie settings are normally handled by the Wonderwall/ingress layer — check that the Ktor app does not double-authenticate or override these.
