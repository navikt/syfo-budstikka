# Authentication decision tree — caller type to mechanism

Identify who initiates the request to this Ktor backend, then choose the
mechanism. Separate inbound validation (what this API accepts) from outbound
tokens (what it obtains for downstream calls).

## Inbound — who calls this API?

| Caller | Authentication mechanism | NAIS flag |
|---|---|---|
| Nav service with user context (OBO) | TokenX | `tokenx.enabled: true` |
| Nav service without user context (batch) | Azure AD client credentials | `azure.application.enabled: true` |
| Case worker (Azure frontend token) | Azure AD | `azure.application.enabled: true` |
| Citizen (through frontend/Wonderwall) | TokenX (frontend exchanges ID-porten) | `tokenx.enabled: true` |
| External partner/system | Maskinporten | `maskinporten.enabled: true` |
| System user (Altinn 3) | Maskinporten plus system user | `maskinporten.enabled: true` |

For a citizen flow, the frontend/BFF exchanges the ID-porten token to TokenX
before this backend is called. `idporten.enabled: true` is unusual for a pure
backend and is only set when the application receives an ID-porten token itself.

## Outbound — this API calls another service

| Must user identity propagate? | Mechanism | Texas `identity_provider` |
|---|---|---|
| Yes, user context exists | TokenX exchange (OBO) | `tokenx` |
| No, pure machine-to-machine | Azure AD client credentials | `azuread` |

## Common error

Using Azure client credentials while user context exists loses identity and makes
per-user authorization impossible.

```text
Wrong: Citizen → Frontend → [Azure client credentials] → this API
Result: identity is lost; per-user authorization is impossible

Correct: Citizen → Frontend → [TokenX] → this API → [TokenX exchange] → downstream
Result: the user's identity (`pid`) follows the request
```

## System user (Altinn 3)

Altinn 3 lets external organisations create a system user that accesses Nav
services through Maskinporten. See [Altinn 3 system-user documentation](https://docs.altinn.studio/authentication/what-do-you-get/systemuser/).
