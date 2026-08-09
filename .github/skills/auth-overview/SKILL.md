---
name: auth-overview
description: "Use when this Ktor backend needs to set up or troubleshoot authentication/authorization — incoming JWT validation, TokenX OBO, Azure AD M2M (client_credentials), the Texas sidecar, accessPolicy in Nais, or when an endpoint needs protecting. Triggers: 'protect this endpoint' / 'beskytt dette endepunktet', 'which auth should this caller use' / 'hvilken auth skal denne kalleren bruke', 'exchange the token' / 'veksle tokenet', plus 401/403 errors and audience/issuer mismatch. Invoked via /auth-overview."
---

# Authentication overview

Reference for authentication and authorization in this repository on NAIS. **Current state (fact):** this backend has no inbound token validation — no `ktor-server-auth` or token-support dependency exists in `build.gradle.kts`, and the Nais manifest defines only `accessPolicy.outbound` (nais-dev.yaml notes tokenx inbound as DEFERRED). The only auth code is outbound: machine-to-machine tokens fetched via the Texas sidecar (`TexasTokenProvider.kt`). The inbound-validation material below is design-time guidance for when an inbound API surface is added — it does not describe code that exists today. The focus is JVM/Ktor — not the frontend.

## Decision tree — caller type → auth mechanism

Identify who initiates the request against this API, and who this API calls in turn.

| Caller into this API                          | Mechanism (incoming validation)           | Nais flag                         |
|-----------------------------------------------|-------------------------------------------|-----------------------------------|
| NAV service with user context (OBO)           | TokenX                                    | `tokenx.enabled: true`            |
| NAV service without user context (batch/job)  | Azure AD client_credentials               | `azure.application.enabled: true` |
| Case worker (token from Azure frontend)       | Azure AD                                  | `azure.application.enabled: true` |
| Citizen (via frontend/Wonderwall)             | TokenX (the frontend exchanges the ID-porten token) | `tokenx.enabled: true`  |
| External partner / system                     | Maskinporten                              | `maskinporten.enabled: true`      |

When this API calls outbound:
- User context must travel along → **TokenX exchange (OBO)**.
- Pure machine-to-machine without a user → **Azure AD client_credentials (M2M)**.

Complete decision tree, counter-example and system user (Altinn 3): see [`references/decision-tree.md`](references/decision-tree.md).

**Note the citizen flow:** the frontend/BFF (Wonderwall) exchanges the ID-porten token for a TokenX token before calling this backend API, so the backend validates a TokenX token. `idporten.enabled: true` is set only if the app itself receives the ID-porten token directly — unusual for a pure backend API.

**Most common mistake:** Azure client_credentials used where user context exists — the user identity is lost and per-user authorization becomes impossible. Use TokenX exchange instead.

## Nais configuration per mechanism

### Azure AD / Entra ID (internal NAV services, M2M)
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
Auto-injected env vars: `AZURE_APP_CLIENT_ID`, `AZURE_APP_CLIENT_SECRET`, `AZURE_APP_WELL_KNOWN_URL`, `AZURE_OPENID_CONFIG_ISSUER`, `AZURE_OPENID_CONFIG_JWKS_URI`, `AZURE_APP_PRE_AUTHORIZED_APPS`.

### TokenX (service-to-service with user context, on-behalf-of)
```yaml
tokenx:
  enabled: true
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
```
Auto-injected env vars: `TOKEN_X_WELL_KNOWN_URL`, `TOKEN_X_CLIENT_ID`, `TOKEN_X_PRIVATE_JWK`, `TOKEN_X_ISSUER`, `TOKEN_X_JWKS_URI`.

### Maskinporten (external organizations)
```yaml
maskinporten:
  enabled: true
  scopes:
    consumes:
      - name: "nav:example/scope"
```

`accessPolicy` is not optional cosmetics: incoming tokens that do not match `inbound.rules` must be rejected at the platform level. Keep the code and the Nais manifest in sync — drift between them is a bug.

## Token validation in Ktor

*(Design-time guidance for a future inbound surface — no inbound validation exists in this repo today; see the note at the top.)*

Use the NAV library `no.nav.security:token-validation-ktor-v3` (umbrella: `navikt/token-support`) for incoming JWT validation. It integrates with Ktor `Authentication` and picks up issuer/JWKS from the Nais env.

```kotlin
// build.gradle.kts
implementation("no.nav.security:token-validation-ktor-v3:<version>")

// Plugin setup — the issuer name matches the issuer config in application.conf
install(Authentication) {
    tokenValidationSupport(
        name = "tokenx",
        config = environment.config, // reads no.nav.security.jwt.issuers.*
    )
}

routing {
    authenticate("tokenx") {
        get("/api/sykmeldinger") {
            val claims = call.principal<TokenValidationContextPrincipal>()
                ?.context?.getClaims("tokenx")
            val pid = claims?.getStringClaim("pid")        // national identity number (TokenX/ID-porten)
            // ... authorize per user based on pid
        }
    }
}
```

Issuer excerpt for `src/main/resources/application.conf` (HOCON — this repo has no `application.yaml`); the plugin reads it via `environment.config`:
```hocon
no.nav.security.jwt.issuers = [
  {
    issuer_name = "tokenx"
    discoveryurl = ${?TOKEN_X_WELL_KNOWN_URL}
    accepted_audience = ${?TOKEN_X_CLIENT_ID}
  }
]
```

### Alternative: the Texas sidecar (validation + issuance without an OAuth library)
Texas runs as a sidecar in the pod (enabled via the `texas.nais.io/enabled: "true"` annotation in `nais/nais-dev.yaml`/`nais-prod.yaml`) and handles token operations. Its endpoints come from NAIS-injected env vars — this repo reads `NAIS_TOKEN_ENDPOINT` into `tokenEndpoint` (`application.conf`); never hardcode a host or port. Useful when you want to avoid an OAuth library in the app. **Detect what the repository already uses before choosing** — this repo already uses Texas via `TexasTokenProvider.kt`; do not mix token-support and Texas without reason, and do not switch auth library without an explicit mandate.

Introspect (validation of an incoming token):
```
POST $NAIS_TOKEN_INTROSPECTION_ENDPOINT
Content-Type: application/json

{ "identity_provider": "tokenx", "token": "<token to validate>" }
```

## Outgoing tokens (this API calling downstream)

### TokenX exchange (OBO — user context travels along)
```
POST $NAIS_TOKEN_EXCHANGE_ENDPOINT
Content-Type: application/json

{ "identity_provider": "tokenx", "target": "cluster:namespace:app", "user_token": "<incoming user token>" }
```

### Azure AD / Entra ID client_credentials (M2M — no user)

This is what this repo does today, via `TexasTokenProvider.kt` (endpoint from `NAIS_TOKEN_ENDPOINT`, provider `entra_id` — see `application.conf` and `TexasTokenProviderTest.kt`; the Kotlin code sends the same two fields form-encoded via `submitForm`):
```
POST $NAIS_TOKEN_ENDPOINT
Content-Type: application/json

{ "identity_provider": "entra_id", "target": "api://cluster.namespace.app/.default" }
```

**Audience format:**
- Azure AD: `api://cluster.namespace.app/.default`
- TokenX: `cluster:namespace:app`

**Caching:** this repo deliberately caches tokens per target until 30 s before expiry, with a per-target lock against request stampedes (`TexasTokenProvider.kt` — its KDoc explains the rationale: Texas never returns an expired token, but caching saves a sidecar round trip on every downstream call). Do not add a second cache layer on top of `TexasTokenProvider`, and never cache a token without expiry-skew handling.

## NAV-specific JWT claims
- `pid` — national identity number (TokenX / ID-porten). PII — never log it.
- `NAVident` — the case worker's identifier (Azure AD).
- `oid` — object ID in Azure AD.
- `azp` — authorized party (M2M). Validate against `AZURE_APP_PRE_AUTHORIZED_APPS` to know which app is calling.

## Approach
1. Read `src/main/resources/application.conf` (HOCON) and the Nais manifests (`nais/nais-dev.yaml`, `nais/nais-prod.yaml`) to see which mechanisms are configured.
2. Search the codebase for existing auth setup and follow the same pattern — today that is `TexasTokenProvider` in `src/main/kotlin/no/nav/budstikka/infrastructure/auth/`; there is no `tokenValidationSupport`/`Authentication` install.
3. Review the NAV consequences of `accessPolicy` and the auth mechanism with
   `/architecture-review`. When the choice passes the ADR gate, recommend the
   documented route and wait for the user's choice before `/domain-modeling`
   records it.
4. For local runs and JVM tests: see [`references/local-auth-mock.md`](references/local-auth-mock.md).
5. Changes to auth validation or accessPolicy → run `/security-review` before delivery and return the evidence to the calling workflow's verify step.

NAIS docs: https://doc.nais.io/auth/ · Golden Path: https://sikkerhet.nav.no/docs/goldenpath/

## Boundaries

### Always
- Validate incoming JWTs on any future inbound surface: issuer, audience, expiry and signature (let the plugin/Texas introspection do this — never hand-roll it or turn it off).
- Validate `azp` against `AZURE_APP_PRE_AUTHORIZED_APPS` for M2M tokens.
- Cross-check auth code against the Nais manifest's `accessPolicy.inbound.rules` (drift = bug).
- Use env variables from Nais — never hardcode issuer, client id or secrets.
- HTTPS only for token transport.

### Ask first
- Changing accessPolicy in production.
- Changing token validation rules, audience or OAuth scopes.
- Switching auth library (token-support ↔ Texas).

### Never
- Hardcode client secrets or tokens.
- Log whole JWTs or PII claims (`pid`, `NAVident`).
- Skip token validation "just for testing".
- Add a second token-cache layer on top of `TexasTokenProvider` (it already caches per target until just before expiry), or cache a token without expiry-skew handling.
