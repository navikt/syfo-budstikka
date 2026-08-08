# Outgoing HttpClient — calls to a downstream service

What this Ktor backend (`no.nav.budstikka`) actually does when it calls another service itself, and where the boundaries are. Which token mechanism a call needs is owned by `/auth-overview`.

## The client that exists

`authModule()` in `infrastructure/auth/config/Module.kt` provides a single `HttpClient(CIO)` with no configuration block, closed on shutdown via `.cleanup(HttpClient::close)`. `clientModule()` injects that same instance into `PdlClient`, `KrrClient` and `DocumentDistributionClient`, and `TexasTokenProvider` uses it too. Know this before writing code against it:

- **No `HttpTimeout`, no `HttpRequestRetry`, no client `Logging`.** Calls inherit the CIO engine defaults and nothing is retried anywhere.
- **No `ContentNegotiation`** — neither `ktor-client-content-negotiation` nor a serialization converter is on the classpath. Every client reads `bodyAsText()` and (de)serializes explicitly: `sharedJson` in `infrastructure/client/Serialization.kt` (`ignoreUnknownKeys = true`), or a client-local `Json` in `DocumentDistributionClient` that also sets `encodeDefaults = false`.
- **`expectSuccess` is left at its default**, so a 4xx or 5xx arrives as an ordinary `HttpResponse` and each client decides what it means.

Because the client is shared, installing a timeout or a retry policy changes behaviour for every downstream *and* for the Texas token request. That is a decision to make deliberately — not something to add in passing on the assumption it was already meant to be there.

## `Nav-Call-Id` on outgoing calls

The server-side `CallId` plugin in `api/Plugins.kt` applies to inbound requests only, and the only inbound routes are the internal probes — so no inbound callId reaches these clients. Of the three, only `DocumentDistributionClient` sends `Nav-Call-Id`, and the value is the Kafka event id (`request.eventId`), which is what correlates a distribution back to the dispatch that caused it. If you add the header to another client, carry that same identifier rather than minting a second correlation id.

## Map the downstream status at the client boundary

An `HttpResponse` must not escape the client — nothing outside `infrastructure/client/` should branch on a downstream status code. The existing clients turn the response into a domain value at the boundary, in three different shapes:

- **Enumerate the statuses** when several outcomes are meaningful to the caller — `DocumentDistributionClient.kt` maps `when (status)` onto `DistributionResponse.Ok` / `NotOk`, so a 409 counts as success and a dead recipient with unknown address (410) is an ordinary answer rather than a failure.
- **Check for success** when only 2xx is meaningful — `KrrClient.parseIsReserved` opens with `check(status.isSuccess()) { "KRR responded with status ${status.value}" }`.
- **Ignore the status and read the body** when the service signals failure in the payload — `PdlClient` never inspects `status`; `parseIsDead` raises when the GraphQL response carries an `errors` array, so a GraphQL error surfaces as a failure instead of being read as "not dead". Do not copy this shape to a service that signals with status codes.

Everything unhandled raises, with a message that names the service and the status (`"Dokdistfordeling returned unexpected status ${status.value}"`) — that string is what an on-call engineer reads first. A malformed body on an otherwise successful response raises too, with its own message.

**Do not retain the exception cause when a body can contain personal data.** `KrrClient.parseIsReserved` catches `SerializationException` and throws a fresh error without the cause on purpose: the parser's message echoes the offending body, which would put a national identity number into a stacktrace. `DocumentDistributionClient.parseOrderId` does the same for both `SerializationException` and `IllegalArgumentException`.

## Boundaries

- **Circuit breaker** is not available natively in the Ktor client — use Resilience4j if an unstable downstream dependency requires it.
- **If you introduce retries, retry only what is safe to repeat**: idempotent GET/PUT/DELETE, or POST with an idempotency key. Never blindly retry a writing POST without idempotency. Note that the two POSTing clients here (PDL's GraphQL query, dokdist's distribution request) are not equivalent on that point.
- **Never log a response body containing PII** — log status plus the correlating id.
- **Tokens come from `TokenProvider`**, never from a hardcoded value or a shared variable. Every client calls `tokenProvider.token(config.scope)` and sets it with `bearerAuth`; the scope comes from that client's own config entry. Today all of them use Azure AD machine-to-machine through the Texas sidecar, and no TokenX path is wired. Adding a call that needs user context is an `/auth-overview` question first.
