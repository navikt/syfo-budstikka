# API security — NAV signal

Generic API security (CORS setup, CSP/X-Frame-Options/HSTS, Ktor `Authentication`/`CORS` plugin boilerplate, rate-limit filter code, cookie `Secure/HttpOnly/SameSite`, session fixation, CSRF theory) is out of scope — the LLM knows this, and the auth setup in the code is covered by `/auth-overview`. This reference covers only the NAV-specific signal.

## Traceability with Nav-Call-Id

`Nav-Call-Id` must be propagated through the entire chain. In Ktor it is set at the entry point by the `CallId` plugin, placed in MDC for structured logging, and passed on with every downstream call.

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)               // or "Nav-Call-Id"
    generate { UUID.randomUUID().toString() }
    verify { it.isNotBlank() }
}
install(CallLogging) {
    callIdMdc("callId")                          // available in all log lines
}
```

In client calls to other NAV services: set `Nav-Call-Id` from MDC on the request, do not generate a new one. The header is used for correlation, audit and troubleshooting across services. It is not tied to `accessPolicy`, which is NAIS's network control, and it is *never* used as a basis for authorization.

## Nav-Consumer-Id for rate limiting and audit

For rate limiting against internal consumers: use `Nav-Consumer-Id` as the key before you fall back on IP. That gives meaningful limits per consumer app, not per NAIS pod.

## accessPolicy is the primary mechanism

CORS, IP allowlisting and self-validation of `Origin` are secondary. The primary network defense on the NAIS platform is `accessPolicy.inbound/outbound`. See the SKILL.md section "accessPolicy as first-line defense".

For frontend services (with Wonderwall in front): CSRF protection and cookie settings are normally handled by the Wonderwall/ingress layer. Check that the Ktor app does not double-authenticate or override these.

## Selected OWASP API Top 10:2023 signals for NAV

Use the table as a quick check during review. It shows a selection of signals and does not replace the NAV assessments in SKILL.md or the threat model.

| OWASP API | Typical NAV signal | Check in practice |
|-----------|-------------------|-----------------|
| API1 Broken Object Level Authorization | A user or employee can look up a resource with an ID they do not own | Verify ownership/case affiliation in the route handler, not just that the token is valid |
| API2 Broken Authentication | Wrong issuer/audience or a missing `azp` check | Check JWT validation in the `authenticate(...)` block, pre-authorized apps and the correct auth mechanism |
| API3 Broken Object Property Level Authorization | The API returns or accepts fields the client should not see or control | Use explicit DTOs, do not expose internal fields or allow mass assignment uncritically |
| API4 Unrestricted Resource Consumption | Expensive calls can be spammed or drain CPU/memory | Check pagination, payload limits, rate limiting and expensive business flows |
| API5 Broken Function Level Authorization | Ordinary users reach admin or case worker functions | Check role boundaries (`claims.groups`), group checks and dedicated branches for kode 6/7 and egen ansatt |
| API7 SSRF | The API takes a forwarding URL or host from input | Restrict outbound with `accessPolicy`, allowlist hosts and validate the destination |
| API8 Security Misconfiguration | Open ingress, wrong `accessPolicy`, a debug endpoint or wrong CORS | Check the manifest, ingress, internal endpoints and that Wonderwall/NAIS is not overridden |
| API10 Unsafe Consumption of APIs | A third-party API is trusted more than your own input | Validate responses, timeouts, retry strategy and data minimization for external calls |
