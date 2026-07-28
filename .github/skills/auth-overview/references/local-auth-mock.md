# Local authentication mocking for Ktor tests

Run token validation locally and in JUnit/Ktor tests without real ID-porten,
Azure, or TokenX. Use `mock-oauth2-server` as the OIDC issuer.

## JVM tests (the primary option here)

Use `no.nav.security:mock-oauth2-server` directly in tests; Docker is unnecessary.

```kotlin
// build.gradle.kts
testImplementation("no.nav.security:mock-oauth2-server:<version>")
```

```kotlin
// In the test
val mockServer = MockOAuth2Server().apply { start() }

// Point token-validation configuration at the mock issuer
val discoveryUrl = mockServer.wellKnownUrl("tokenx").toString()

// Issue a test JWT with the needed claims
val testJwt = mockServer.issueToken(
    issuerId = "tokenx",
    subject = "test-subject",
    claims = mapOf("pid" to "00000000000", "acr" to "Level4"),
).serialize()

// Use the test JWT as Bearer in a Ktor testHost request
client.get("/api/sykmeldinger") {
    header(HttpHeaders.Authorization, "Bearer $testJwt")
}

mockServer.shutdown()
```

If inbound auth is introduced in `application.conf`, point a test profile's
discovery URL and accepted audience at the mock server. The repository does not
currently contain this inbound configuration.

## Local Docker run (optional)

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

Point `TOKEN_X_WELL_KNOWN_URL` / `AZURE_APP_WELL_KNOWN_URL` at
`http://mock-oauth2:8080/<issuer>/.well-known/openid-configuration`.

## Test data (FNR)

Use only `00000000000` or [Skatteetaten's synthetic series](https://www.skatteetaten.no/skjema/testdata/), and label it explicitly as synthetic. Never use real national identity numbers. Fill placeholders `<version>` and `<GENERATED_JWK>` during setup.
