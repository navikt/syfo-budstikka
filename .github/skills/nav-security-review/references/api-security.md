# API security — Nav signals

Generic API security (CORS configuration, CSP/X-Frame-Options/HSTS, Ktor
`Authentication`/`CORS` boilerplate, rate-limit filter code, cookie
`Secure`/`HttpOnly`/`SameSite`, session fixation, and CSRF theory) is out of
scope. The model already knows it, and `/auth-overview` covers auth setup in
code. This reference covers only Nav-specific signals.

## Traceability with Nav-Call-Id

Propagate `Nav-Call-Id` through the whole chain. In Ktor, set it at ingress with
the `CallId` plugin, put it in MDC for structured logging, and send it on every
downstream call.

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)               // or "Nav-Call-Id"
    generate { UUID.randomUUID().toString() }
    verify { it.isNotBlank() }
}
install(CallLogging) {
    callIdMdc("callId")                          // available in every log line
}
```

When calling another Nav service, set `Nav-Call-Id` from MDC on the request; do
not generate a new value. The header supports correlation, audit, and
troubleshooting across services. It is not connected to `accessPolicy`, NAIS's
network control, and must **never** authorise a request.

## Nav-Consumer-Id for rate limiting and audit

For rate limiting internal consumers, use `Nav-Consumer-Id` as the key before
falling back to IP. This gives meaningful limits per consumer application,
rather than per NAIS pod.

## accessPolicy is the primary mechanism

CORS, IP allowlists, and custom `Origin` validation are secondary. NAIS
`accessPolicy.inbound/outbound` is the primary network defence. Read the
SKILL.md section “accessPolicy as first-line defence”.

For frontend services behind Wonderwall, CSRF protection and cookie settings are
normally handled by Wonderwall or ingress. Verify that the Ktor application does
not authenticate twice or override them.

## Selected OWASP API Top 10:2023 signals for Nav

Use this table as a fast review check. It gives selected signals and does not
replace the Nav assessments in SKILL.md or the threat model.

| OWASP API | Typical Nav signal | Practical check |
|-----------|-------------------|-----------------|
| API1 Broken Object Level Authorization | A user or employee can look up a resource they do not own | Verify ownership/case association in the route handler, not only token validity |
| API2 Broken Authentication | Wrong issuer/audience or missing `azp` check | Check JWT validation in `authenticate(...)`, pre-authorised apps, and the correct auth mechanism |
| API3 Broken Object Property Level Authorization | API returns or accepts fields the client must not see or control | Use explicit DTOs; do not expose internal fields or allow mass update uncritically |
| API4 Unrestricted Resource Consumption | Expensive calls can exhaust CPU or memory | Check pagination, payload limits, rate limiting, and expensive business flows |
| API5 Broken Function Level Authorization | Ordinary users reach admin or caseworker functions | Check role boundaries (`claims.groups`), group checks, and separate branches for code 6/7 and own employee |
| API7 SSRF | API fetches a URL or host from input | Restrict outbound through `accessPolicy`, allowlist hosts, and validate destination |
| API8 Security Misconfiguration | Open ingress, wrong `accessPolicy`, debug endpoint, or wrong CORS | Check manifests, ingress, internal endpoints, and that Wonderwall/NAIS is not overridden |
| API10 Unsafe Consumption of APIs | Third-party API is trusted more than own input | Validate responses, timeouts, retry strategy, and data minimisation for external calls |
