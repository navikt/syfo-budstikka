# Lokal auth-mocking for Ktor-tester

Slik kjører du token-validering lokalt og i JUnit/Ktor-tester uten ekte ID-porten/Azure/TokenX. Bruk `mock-oauth2-server` som OIDC-utsteder.

## JVM-tester (primær for dette repoet)

Bruk `no.nav.security:mock-oauth2-server` direkte fra testene — ingen Docker nødvendig.

```kotlin
// build.gradle.kts
testImplementation("no.nav.security:mock-oauth2-server:<versjon>")
```

```kotlin
// I testen
val mockServer = MockOAuth2Server().apply { start() }

// Pek token-validation-konfigurasjonen mot mock-utstederen
val discoveryUrl = mockServer.wellKnownUrl("tokenx").toString()

// Utsted et test-token med ønskede claims
val token = mockServer.issueToken(
    issuerId = "tokenx",
    subject = "test-subject",
    claims = mapOf("pid" to "00000000000", "acr" to "Level4"),
).serialize()

// Bruk token-et som Bearer i Ktor testHost-kall
client.get("/api/sykmeldinger") {
    header(HttpHeaders.Authorization, "Bearer $token")
}

mockServer.shutdown()
```

I `application.yaml` for testprofil settes `discoveryurl` og `accepted_audience` mot mock-serveren.

## Lokal kjøring med Docker (valgfritt)

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
Pek appens `TOKEN_X_WELL_KNOWN_URL` / `AZURE_APP_WELL_KNOWN_URL` mot `http://mock-oauth2:8080/<issuer>/.well-known/openid-configuration`.

## Testdata (fnr)

Bruk kun `00000000000` eller [Skatteetatens syntetiske serie](https://www.skatteetaten.no/skjema/testdata/). Merk tydelig i testen at dette er syntetisk. Aldri ekte fødselsnumre.

Plassholdere `<versjon>` og `<GENERATED_JWK>` fylles inn ved oppsett.
