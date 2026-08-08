# Outgoing HttpClient — calls to a downstream service

Concrete setup for when this Ktor backend (`no.nav.budstikka`) calls another service itself. Token exchange (TokenX OBO / Azure AD M2M) is owned by `/auth-overview` — here we cover client setup, timeout/retry, tracing and the error contract.

## Client with timeout, retry and logging

```kotlin
// Dependencies via ktorLibs.client.* (engine + ContentNegotiation) — not hand-written versions
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

## Translate downstream errors into the error contract BEFORE StatusPages

A non-2xx response from downstream must not leak raw to our client. Map it to the repository's `ApiErrorException` (see `/kotlin-ktor` → references/error-handling.md), so that `StatusPages` yields a uniform `ApiError`:

```kotlin
suspend fun HttpResponse.toDomainOrThrow(): Noe = when (status.value) {
    in 200..299 -> body()
    401, 403    -> throw ApiErrorException.InternalServerErrorException("Tjenesten er midlertidig utilgjengelig")
    404         -> throw ApiErrorException.NotFoundException("Fant ikke ressursen")
    in 500..599 -> throw ApiErrorException.InternalServerErrorException("Tjenesten er midlertidig utilgjengelig")
    else        -> throw ApiErrorException.InternalServerErrorException("Det oppsto en uventet feil")
}
```

## Boundaries

- **Circuit breaker** is not available natively in the Ktor client — use Resilience4j if an unstable downstream dependency requires it.
- **Retry only what is safe to repeat**: idempotent GET/PUT/DELETE, or POST with an idempotency key. Never blindly retry a writing POST without idempotency.
- **Never log a response body containing PII** — log status + callId.
- **Tokens are obtained per `/auth-overview`** (TokenX exchange when there is user context, Azure AD M2M otherwise) — do not hardcode or share tokens across users.
