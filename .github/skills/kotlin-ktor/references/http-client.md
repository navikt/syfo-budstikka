# Outbound HttpClient — calls to a downstream service

Concrete pattern for when this Ktor backend (`no.nav.budstikka`) calls a new
downstream service. Read existing clients first. Token exchange belongs to
`/auth-overview`; this reference covers timeout/retry, tracing, and error
translation when the new contract needs them.

## Client with timeout, retry, and logging

```kotlin
// Follow the repository's libs.ktor.client.* style; add ContentNegotiation only when needed.
val httpClient = HttpClient(CIO) {
    expectSuccess = false                        // map status ourselves (see below)
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

## Propagate `Nav-Call-Id` on outbound calls

```kotlin
suspend fun hentNoe(callId: String): Noe {
    val response = httpClient.get("$baseUrl/api/noe") {
        header("Nav-Call-Id", callId)            // same callId set by the CallId plugin
        header(HttpHeaders.Authorization, "Bearer ${token()}")  // see /auth-overview for token handling
    }
    return response.toDomainOrThrow()
}
```

## Translate downstream failures to the error contract before StatusPages

A non-2xx downstream response must not leak raw to our client. Map it to the
repository’s `ApiErrorException` (see `/kotlin-ktor` →
references/error-handling.md), so `StatusPages` produces a consistent `ApiError`:

```kotlin
suspend fun HttpResponse.toDomainOrThrow(): Noe = when (status.value) {
    in 200..299 -> body()
    401, 403    -> throw ApiErrorException.InternalServerErrorException("Downstream rejected our token")
    404         -> throw ApiErrorException.NotFoundException("Resource not found downstream")
    in 500..599 -> throw ApiErrorException.InternalServerErrorException("Downstream service unavailable")
    else        -> throw ApiErrorException.InternalServerErrorException("Unexpected downstream response: ${status.value}")
}
```

## Boundaries

- **Circuit breaker** is not native to the Ktor client. Use Resilience4j when an
  unstable downstream dependency requires it.
- **Retry only operations safe to repeat**: idempotent GET/PUT/DELETE, or POST
  with an idempotency key. Never blindly retry a writing POST without idempotency.
- **Never log response bodies with PII**; log status and callId.
- **Retrieve tokens through `/auth-overview`**: TokenX exchange for user context,
  Azure AD M2M otherwise. Never hard-code or share a token across users.
