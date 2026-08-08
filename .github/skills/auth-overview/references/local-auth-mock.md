# Local auth mocking for Ktor tests

How to run token validation locally and in JUnit/Ktor tests without a real ID-porten/Azure/TokenX. Use `mock-oauth2-server` as the OIDC issuer.

## JVM tests (primary for this repository)

Use `no.nav.security:mock-oauth2-server` directly from the tests — no Docker needed.

```kotlin
// build.gradle.kts
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

In `application.yaml` for the test profile, point `discoveryurl` and `accepted_audience` at the mock server.

## Running locally with Docker (optional)

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
