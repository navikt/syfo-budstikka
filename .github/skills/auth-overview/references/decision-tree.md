# Auth decision tree — caller type → mechanism

Identify who initiates the request against this backend API, and choose the auth mechanism accordingly. Distinguish between incoming validation (what this API accepts) and outgoing tokens (what this API fetches itself in order to call downstream).

## Incoming — who calls this API

| Caller                                     | Auth mechanism               | Nais flag                         |
|--------------------------------------------|------------------------------|-----------------------------------|
| NAV service with user context (OBO)        | TokenX                       | `tokenx.enabled: true`            |
| NAV service without user context (batch)   | Azure AD client_credentials  | `azure.application.enabled: true` |
| Case worker (token from Azure frontend)    | Azure AD                     | `azure.application.enabled: true` |
| Citizen (via frontend/Wonderwall)          | TokenX (the frontend exchanges ID-porten) | `tokenx.enabled: true`            |
| External partner / system                  | Maskinporten                 | `maskinporten.enabled: true`      |
| System user (Altinn 3)                     | Maskinporten + system user   | `maskinporten.enabled: true`      |

> **Note the citizen flow:** the frontend/BFF (Wonderwall) exchanges the ID-porten token for a TokenX token before calling this backend API — the backend then validates a TokenX token. `idporten.enabled: true` on a pure backend API is unusual (it is set only if the app itself receives the ID-porten token directly).

## Outgoing — this API calls another service

| Should the user's identity travel along? | Mechanism                     | Texas `identity_provider` |
|------------------------------------------|-------------------------------|---------------------------|
| Yes (user context exists)                | TokenX exchange (OBO)         | `tokenx`                  |
| No (pure machine-to-machine)             | Azure AD client_credentials   | `azuread`                 |

## Common mistake

Azure client_credentials used where user context exists — the identity is lost and per-user authorization becomes impossible.

```
❌ WRONG:
Citizen → Frontend → [Azure client_credentials] → this API
   Consequence: Loses who the user is, cannot authorize per user

✅ RIGHT:
Citizen → Frontend → [TokenX] → this API → [TokenX exchange] → downstream
   Consequence: The user's identity (pid) travels along the whole way
```

## System user (Altinn 3)

A mechanism in Altinn 3 where external organizations create a system user that grants access to NAV services via Maskinporten. See the [Altinn 3 system user documentation](https://docs.altinn.studio/authentication/what-do-you-get/systemuser/).
