# Outgoing HttpClient — calls to a downstream service

Concrete setup for when this Ktor backend (`no.nav.budstikka`) calls another service itself. Token exchange (TokenX OBO / Azure AD M2M) is owned by `/auth-overview` — here we cover client setup, timeout/retry, tracing and downstream error mapping.

## Client with timeout, retry and logging

```kotlin
// Engine + ContentNegotiation come from the version catalogue — do not hand-write versions
val httpClient = HttpClient(CIO) {
    expectSuccess = false                        // we map the status ourselves (see below)
    install(ContentNegotiation) { json() }
    install(HttpTimeout) {
        connectTimeoutMillis = 3_000
        requestTimeoutMillis = 10_000
        socketTimeoutMillis = 10_000
    }
    install(HttpRequestRetry) {
        retryOnExceptionOrServerErrors(maxRetries = 3)   // 5xx + IOException
        exponentialDelay()
    }
    install(Logging) { level = LogLevel.INFO }   // never log a body containing PII
}
```

## Propagate `Nav-Call-Id` on outgoing calls

```kotlin
suspend fun hentNoe(callId: String): Noe {
    val response = httpClient.get("$baseUrl/api/noe") {
        header("Nav-Call-Id", callId)            // the same callId the CallId plugin set
        header(HttpHeaders.Authorization, "Bearer ${token()}")  // token: se /auth-overview
    }
    return response.toDomainOrThrow()
}
```

## Map the downstream status at the client boundary

An `HttpResponse` must not escape the client — nothing outside `infrastructure/client/` should branch on a downstream status code. The existing clients turn the response into a domain value at the boundary, in one of two shapes:

- **Enumerate the statuses** when several outcomes are meaningful to the caller, and return a domain result — `DocumentDistributionClient.kt` maps `when (status)` onto `DistributionResponse.Ok` / `NotOk`, so a dead recipient (410) is an ordinary answer rather than a failure.
- **Check for success** when only 2xx is meaningful — `KrrClient.kt` uses `check(status.isSuccess())`.

Everything unhandled raises, with a message that names the service and the status (`error("KRR responded with status ${status.value}")`) — that string is what an on-call engineer reads first. A malformed success body counts as unhandled.

**Do not retain the exception cause when a body can contain personal data.** `KrrClient.parseIsReserved` catches `SerializationException` and throws a fresh error without the cause on purpose: the parser's message echoes the offending body, which would put a national identity number into a stacktrace.

## Boundaries

- **Circuit breaker** is not available natively in the Ktor client — use Resilience4j if an unstable downstream dependency requires it.
- **Retry only what is safe to repeat**: idempotent GET/PUT/DELETE, or POST with an idempotency key. Never blindly retry a writing POST without idempotency.
- **Never log a response body containing PII** — log status + callId.
- **Tokens are obtained per `/auth-overview`** (TokenX exchange when there is user context, Azure AD M2M otherwise) — do not hardcode or share tokens across users.
