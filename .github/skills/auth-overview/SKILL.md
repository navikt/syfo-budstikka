---
name: auth-overview
description: "Bruk når dette Ktor-backendet (no.nav.syfo) skal sette opp eller feilsøke autentisering/autorisering — innkommende JWT-validering, TokenX OBO, Azure AD M2M (client_credentials), Texas-sidecar, accessPolicy i Nais, eller når et endepunkt skal beskyttes. Trigger: 'beskytt dette endepunktet', 401/403-feil, 'hvilken auth skal denne kalleren ha', token-utveksling mot nedstrøms-tjeneste, audience/issuer-mismatch. Kalles via /auth-overview."
---

# Autentiseringsoversikt — Ktor-backend (no.nav.syfo)

Referanse for autentisering og autorisering i dette Ktor-backendet på NAIS. Backend mottar tokens fra kallere og kaller selv nedstrøms-tjenester. Fokus er JVM/Ktor — ikke frontend.

## Beslutningstre — caller-type → auth-mekanisme

Identifiser hvem som initierer forespørselen mot dette API-et, og hvem dette API-et selv kaller.

| Kaller mot dette API-et                      | Mekanisme (innkommende validering)   | Nais-flagg                        |
|----------------------------------------------|--------------------------------------|-----------------------------------|
| NAV-tjeneste med brukerkontekst (OBO)        | TokenX                               | `tokenx.enabled: true`            |
| NAV-tjeneste uten brukerkontekst (batch/job) | Azure AD client_credentials          | `azure.application.enabled: true` |
| Saksbehandler (token fra Azure-frontend)     | Azure AD                             | `azure.application.enabled: true` |
| Innbygger (token fra ID-porten-frontend)     | ID-porten / TokenX                  | `idporten.enabled: true`          |
| Ekstern partner / system                     | Maskinporten                         | `maskinporten.enabled: true`      |

Når dette API-et kaller utgående:
- Brukerkontekst skal følge med → **TokenX exchange (OBO)**.
- Ren maskin-til-maskin uten bruker → **Azure AD client_credentials (M2M)**.

Komplett beslutningstre, mot-eksempel og systembruker (Altinn 3): se [`references/decision-tree.md`](references/decision-tree.md).

**Vanligste feil:** Azure client_credentials brukt der brukerkontekst finnes — brukeridentiteten tapes og per-bruker-autorisasjon blir umulig. Bruk TokenX-exchange i stedet.

## Nais-konfigurasjon per mekanisme

### Azure AD / Entra ID (interne NAV-tjenester, M2M)
```yaml
azure:
  application:
    enabled: true
    tenant: nav.no
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
  outbound:
    rules:
      - application: nedstroms-app
        namespace: team-nedstroms
```
Auto-injiserte env: `AZURE_APP_CLIENT_ID`, `AZURE_APP_CLIENT_SECRET`, `AZURE_APP_WELL_KNOWN_URL`, `AZURE_OPENID_CONFIG_ISSUER`, `AZURE_OPENID_CONFIG_JWKS_URI`, `AZURE_APP_PRE_AUTHORIZED_APPS`.

### TokenX (service-to-service med brukerkontekst, on-behalf-of)
```yaml
tokenx:
  enabled: true
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
```
Auto-injiserte env: `TOKEN_X_WELL_KNOWN_URL`, `TOKEN_X_CLIENT_ID`, `TOKEN_X_PRIVATE_JWK`, `TOKEN_X_ISSUER`, `TOKEN_X_JWKS_URI`.

### Maskinporten (eksterne organisasjoner)
```yaml
maskinporten:
  enabled: true
  scopes:
    consumes:
      - name: "nav:example/scope"
```

`accessPolicy` er ikke valgfri kosmetikk: innkommende tokens som ikke matcher `inbound.rules` skal avvises på plattformnivå. Hold koden og Nais-manifestet i sync — drift mellom dem er en feil.

## Token-validering i Ktor

Bruk NAV-biblioteket `no.nav.security:token-validation-ktor-v3` (paraply: `navikt/token-support`) for innkommende JWT-validering. Det integrerer med Ktor `Authentication` og henter issuer/JWKS fra Nais-env.

```kotlin
// build.gradle.kts
implementation("no.nav.security:token-validation-ktor-v3:<versjon>")

// Plugin-oppsett — issuer-navn matcher application.yaml
install(Authentication) {
    tokenValidationSupport(
        name = "tokenx",
        config = environment.config, // leser no.nav.security.jwt.issuers.*
    )
}

routing {
    authenticate("tokenx") {
        get("/api/sykmeldinger") {
            val claims = call.principal<TokenValidationContextPrincipal>()
                ?.context?.getClaims("tokenx")
            val pid = claims?.getStringClaim("pid")        // fødselsnummer (TokenX/ID-porten)
            // ... autoriser per bruker basert på pid
        }
    }
}
```

`application.yaml`-utdrag (issuer-konfig leses av plugin-en):
```yaml
no.nav.security.jwt.issuers:
  - issuer_name: tokenx
    discoveryurl: ${TOKEN_X_WELL_KNOWN_URL}
    accepted_audience: ${TOKEN_X_CLIENT_ID}
```

### Alternativ: Texas-sidecar (validering + utstedelse uten OAuth-bibliotek)
Texas kjører på `localhost:3000` i podden og håndterer token-operasjoner. Nyttig når du vil unngå OAuth-bibliotek i appen. **Detekter hva repoet allerede bruker før du velger** — ikke bland token-support og Texas uten grunn, og ikke bytt auth-bibliotek uten eksplisitt oppdrag.

Introspect (validering av innkommende token):
```
POST http://localhost:3000/api/v1/introspect
Content-Type: application/json

{ "identity_provider": "tokenx", "token": "<token som skal valideres>" }
```

## Utgående tokens (dette API kaller nedstrøms)

### TokenX exchange (OBO — brukerkontekst følger med)
```
POST http://localhost:3000/api/v1/token/exchange
Content-Type: application/json

{ "identity_provider": "tokenx", "target": "cluster:namespace:app", "user_token": "<innkommende brukertoken>" }
```

### Azure AD client_credentials (M2M — ingen bruker)
```
POST http://localhost:3000/api/v1/token
Content-Type: application/json

{ "identity_provider": "azuread", "target": "api://cluster.namespace.app/.default" }
```

**Audience-format:**
- Azure AD: `api://cluster.namespace.app/.default`
- TokenX: `cluster:namespace:app`

**Caching:** Texas cacher tokens med 60 s preemptiv refresh. Ikke implementer egen token-caching.

## NAV-spesifikke JWT-claims
- `pid` — fødselsnummer (TokenX / ID-porten). PII — aldri logg.
- `NAVident` — saksbehandlerens identifikator (Azure AD).
- `oid` — objekt-ID i Azure AD.
- `azp` — authorized party (M2M). Valider mot `AZURE_APP_PRE_AUTHORIZED_APPS` for å vite hvilken app som kaller.

## Tilnærming
1. Les `src/main/resources/application.yaml` og Nais-manifestet for å se hvilke mekanismer som er konfigurert.
2. Søk i kodebasen etter eksisterende auth-oppsett (`tokenValidationSupport`, `Authentication`, Texas-kall) og følg samme mønster.
3. Beslutninger om accessPolicy / valg av auth-mekanisme dokumenteres som ADR i `docs/adr/` (jf. @grillmester fase 1–2, `/grill-with-docs`).
4. For lokal kjøring og JVM-tester: se [`references/local-auth-mock.md`](references/local-auth-mock.md).
5. Endringer i auth-validering eller accessPolicy → kjør `/security-review` før levering (@grillmester fase 5, loggføres i `.grill/VERIFICATION.md`).

NAIS-dok: https://doc.nais.io/auth/ · Golden Path: https://sikkerhet.nav.no/docs/goldenpath/

## Grenser

### Alltid
- Valider innkommende JWT: issuer, audience, utløpstid og signatur (gjør plugin-en/Texas — ikke skru av).
- Valider `azp` mot `AZURE_APP_PRE_AUTHORIZED_APPS` for M2M-tokens.
- Kryssjekk auth-kode mot Nais-manifestets `accessPolicy.inbound.rules` (drift = feil).
- Bruk env-variabler fra Nais — aldri hardkod issuer, client-id eller hemmeligheter.
- Kun HTTPS for tokenoverføring.

### Spør først
- Endring i accessPolicy i produksjon.
- Endring i tokenvalideringsregler, audience eller OAuth-scopes.
- Bytte av auth-bibliotek (token-support ↔ Texas).

### Aldri
- Hardkode klienthemmeligheter eller tokens.
- Logge hele JWT-er eller PII-claims (`pid`, `NAVident`).
- Hoppe over tokenvalidering "for test".
- Lage egen token-caching (Texas håndterer det).
