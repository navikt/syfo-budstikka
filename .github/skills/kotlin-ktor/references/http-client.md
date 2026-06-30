# Utgående HttpClient — kall mot nedstrøms-tjeneste

Konkret oppsett for når dette Ktor-backendet (`no.nav.syfo`) selv kaller en annen tjeneste. Token-utveksling (TokenX OBO / Azure AD M2M) eies av `/auth-overview` — her dekker vi klient-oppsett, timeout/retry, sporing og feilkontrakt.

## Klient med timeout, retry og logging

```kotlin
// Avhengigheter via ktorLibs.client.* (motor + ContentNegotiation) — ikke håndskrevne versjoner
val httpClient = HttpClient(CIO) {
    expectSuccess = false                        // vi mapper status selv (se under)
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
    install(Logging) { level = LogLevel.INFO }   // aldri logg body med PII
}
```

## Propagér `Nav-Call-Id` på utgående kall

```kotlin
suspend fun hentNoe(callId: String): Noe {
    val response = httpClient.get("$baseUrl/api/noe") {
        header("Nav-Call-Id", callId)            // samme callId som CallId-pluginen satte
        header(HttpHeaders.Authorization, "Bearer ${token()}")  // token: se /auth-overview
    }
    return response.toDomainOrThrow()
}
```

## Oversett nedstrøms-feil til feilkontrakten FØR StatusPages

Et non-2xx-svar fra nedstrøms skal ikke lekke rått til vår klient. Map det til repoets `ApiErrorException` (se `/kotlin-ktor` → references/error-handling.md), så `StatusPages` gir en enhetlig `ApiError`:

```kotlin
suspend fun HttpResponse.toDomainOrThrow(): Noe = when (status.value) {
    in 200..299 -> body()
    401, 403    -> throw ApiErrorException.InternalServerErrorException("Nedstrøms avviste vårt token")
    404         -> throw ApiErrorException.NotFoundException("Fant ikke ressurs nedstrøms")
    in 500..599 -> throw ApiErrorException.InternalServerErrorException("Nedstrøms-tjeneste utilgjengelig")
    else        -> throw ApiErrorException.InternalServerErrorException("Uventet svar nedstrøms: ${status.value}")
}
```

## Grenser

- **Circuit breaker** finnes ikke native i Ktor-klienten — bruk Resilience4j hvis en ustabil nedstrøms-avhengighet krever det.
- **Retry kun det som er trygt å gjenta**: idempotente GET/PUT/DELETE, eller POST med idempotensnøkkel. Aldri blind retry på en skrivende POST uten idempotens.
- **Aldri logg responsbody med PII** — logg status + callId.
- **Token hentes per `/auth-overview`** (TokenX-exchange ved brukerkontekst, Azure AD M2M ellers) — ikke hardkod eller del token på tvers av brukere.
