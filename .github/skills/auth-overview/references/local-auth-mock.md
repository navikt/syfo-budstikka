# Local auth mocking for Ktor tests

How this repository tests auth without real NAIS infrastructure. There is no inbound token validation in this repo to mock — the only auth code is outbound token fetching via the Texas sidecar (`TexasTokenProvider.kt`). Tests are Kotest `FunSpec` (`kotest-runner-junit5` in `build.gradle.kts`), not JUnit test classes.

## Primary pattern for this repository: MockEngine against the Texas endpoint

`TexasTokenProviderTest.kt` (`src/test/kotlin/no/nav/budstikka/infrastructure/auth/`) stubs the Texas sidecar with Ktor's `MockEngine` (`ktor-client-mock` in `gradle/libs.versions.toml`): point `TexasConfig` at a fake URL and let the engine answer with the token JSON. No server process, no Docker.

```kotlin
// Pattern from TexasTokenProviderTest.kt
val config = TexasConfig(tokenEndpoint = "http://texas.local/api/v1/token", identityProvider = "entra_id")
val client =
    HttpClient(
        MockEngine { request ->
            respond(
                content = """{"access_token":"tok-1","expires_in":3600,"token_type":"Bearer"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )
val provider = TexasTokenProvider(client, config, MutableClock(start))
```

Assert on the captured request (`identity_provider` must be `entra_id`, `target` as expected) and on caching behavior by advancing `MutableClock`.

## Hand-written fakes

Collaborators behind interfaces get hand-written fakes in `src/test/kotlin/no/nav/budstikka/fakes/` (e.g. `FakeDocumentDistributor.kt`, `FakeReservationLookup.kt`, `FakeDeathLookup.kt`) — no mocking framework in this repo.

## Future option: mock-oauth2-server for an inbound surface (NOT currently used)

`no.nav.security:mock-oauth2-server` is **not** a dependency here, and there is no inbound validation to point it at. Only if a future inbound authenticated surface (token validation with `navikt/token-support`) is added does it become relevant, as the local OIDC issuer:

```kotlin
// build.gradle.kts (only when inbound validation exists)
testImplementation("no.nav.security:mock-oauth2-server:<version>")
```

```kotlin
// In the test
val mockServer = MockOAuth2Server().apply { start() }

// Point the token-validation configuration at the mock issuer
val discoveryUrl = mockServer.wellKnownUrl("tokenx").toString()

// Issue a test token with the desired claims
val serializedJwt = mockServer.issueToken(
    issuerId = "tokenx",
    subject = "test-subject",
    claims = mapOf("pid" to "<SYNTHETIC_FNR>", "acr" to "Level4"),
).serialize()

// Use the token as Bearer in a Ktor testHost call
client.get("/api/sykmeldinger") {
    header(HttpHeaders.Authorization, "Bearer $serializedJwt")
}

mockServer.shutdown()
```

In the test config, point `discoveryurl` and `accepted_audience` at the mock server — this repo's config file is `src/main/resources/application.conf` (HOCON; there is no `application.yaml`).

### Running locally with Docker (optional, same future scenario)

```yaml
services:
  mock-oauth2:
    image: ghcr.io/navikt/mock-oauth2-server:latest
    ports: ["8080:8080"]
    environment:
      JSON_CONFIG: |
        {
          "interactiveLogin": true,
          "tokenProvider": { "keyProvider": { "initialKeys": "<GENERATED_JWK>" } }
        }
```
Point the app's `TOKEN_X_WELL_KNOWN_URL` / `AZURE_APP_WELL_KNOWN_URL` at `http://mock-oauth2:8080/<issuer>/.well-known/openid-configuration`.

## Test data (national identity numbers)

Use `<SYNTHETIC_FNR>` as the placeholder in templates. Replace it with a value from
[Skatteetaten's synthetic series](https://www.skatteetaten.no/skjema/testdata/)
when runnable code requires a valid format, and mark clearly that the test data is
synthetic. Never use real national identity numbers.

The placeholders `<version>` and `<GENERATED_JWK>` are filled in during setup.
