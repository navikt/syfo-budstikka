# Auth diagnosis — 401 Unauthorized and 403 Forbidden

Diagnostic trees for authentication and authorization failures in this repository's Ktor backend (`no.nav.syfo`). Covers Azure AD, TokenX, ID-porten and Maskinporten via the Texas sidecar (JVM/Kotlin).

See `/auth-overview` for the mechanisms; this file is for *diagnosing when they fail*.

## Decode a JWT

```bash
# Decode the payload without signature verification
echo "{token}" | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .
```

Important fields:
- `iss` (issuer) — who issued the token (`login.microsoftonline.com/...` for Azure AD, the TokenX URL for TokenX, the ID-porten URL for ID-porten)
- `aud` (audience) — who the token is intended for
- `exp` (expiry) — when the token expires
- `sub` / `NAVident` / `pid` — who the token represents
- `azp` (authorized party) — the client the token was issued to

## Check the auth configuration in the pod

```bash
# List all auth-related env vars from NAIS
kubectl get pod {pod} -n {namespace} \
  -o jsonpath='{range .spec.containers[0].env[*]}{.name}={.value}{"\n"}{end}' \
  | grep -E 'AZURE|TOKEN_X|IDPORTEN|MASKINPORTEN|NAIS_TOKEN'

# Test that the JWKS endpoint is reachable from the pod
kubectl exec -n {namespace} {pod} -- \
  wget -qO- --timeout=5 "$AZURE_OPENID_CONFIG_JWKS_URI" 2>&1 | head -1
```

## Check the accessPolicy

```bash
# Show the current accessPolicy (inbound/outbound)
kubectl get app -n {namespace} {app-name} -o yaml | grep -A 30 accessPolicy

# Show the network policies Nais generates
kubectl get networkpolicy -n {namespace} -l app={app-name}
```

## 401 Unauthorized — diagnostic tree

```
401 Unauthorized
├── Does the request have an Authorization header?
│   ├── No → the caller is missing a token. Check the caller / Texas sidecar setup.
│   └── Yes → continue
├── Is the token from the right issuer?
│   ├── Azure AD but TokenX expected → wrong auth flow (use token exchange/OBO, not M2M)
│   ├── ID-porten but Azure AD expected → wrong entry point / wrong sidecar config
│   └── Right issuer → continue
├── Is the audience correct?
│   │   Azure AD: `api://{cluster}.{namespace}.{app}/.default`
│   │   TokenX:   `{cluster}:{namespace}:{app}`
│   ├── Wrong audience → the caller sends the token to the wrong recipient (fix `target` in the token exchange)
│   └── Correct → continue
├── Has the token expired?
│   ├── exp < now → token expired. Texas handles refresh — no separate token caching in the app.
│   └── Valid → continue
├── Is the clock synchronized?
│   ├── Clock skew > a few seconds → infra problem (rare on Nais)
│   └── OK → continue
└── Is JWKS reachable from the pod?
    ├── No → network problem, check `accessPolicy.outbound.external` (login.microsoftonline.com for Azure AD)
    └── Yes → check the token validation config in Ktor (issuer/audience in the `Authentication` installation)
```

## 403 Forbidden — diagnostic tree

```
403 Forbidden
├── Is accessPolicy.inbound configured?
│   ├── No → add the caller to the inbound rules
│   └── Yes → continue
├── Is the caller registered in the inbound list?
│   │   Example:
│   │     accessPolicy.inbound.rules:
│   │       - application: {caller-app}
│   │         namespace: {caller-namespace}
│   ├── No → add the caller
│   └── Yes → continue
└── Is there authorization at the application level?
    ├── Yes → check roles/groups/scopes in the token
    │   - Azure AD: roles via app roles or group claims
    │   - TokenX: forwarded user — check `NAVident` or `pid`
    │   - Maskinporten: check `scope`
    └── No → check the Nais app status: `kubectl get app {name} -o yaml`
```

## Common NAV-specific failure patterns

| Error message | Cause | Fix |
|------------|-------|---------|
| `Token validation failed: wrong issuer` | Token from the wrong IdP | The caller uses the wrong auth mechanism (Azure AD vs. TokenX vs. ID-porten) |
| `Token validation failed: wrong audience` | Token intended for another app | Fix `target` in the Texas token exchange call |
| `Token validation failed: expired` | Token expired | Token cache too old. Texas refreshes on its own — no separate caching in the app. |
| `Connection refused: login.microsoftonline.com` | Cannot reach JWKS/issuer | Add `accessPolicy.outbound.external` for the issuer host |
| `No bearer token found` / 401 on a protected route | Missing `Authorization` header | Check that the caller/sidecar sends the token; check that the route is not unexpectedly outside `authenticate { }` |
| `403 denied by NetworkPolicy` | `accessPolicy.inbound` is missing the caller | Add `{application, namespace}` to inbound.rules |

## Texas sidecar — isolate app from sidecar

The Kotlin backend fetches/exchanges tokens via the Texas sidecar (HTTP on `localhost`, port from the `NAIS_TOKEN_*` env vars). Troubleshoot by `curl`-ing the sidecar from the pod to separate a sidecar problem from an app problem:

```bash
kubectl exec -n {namespace} {pod} -- \
  curl -s "$NAIS_TOKEN_ENDPOINT" -d 'identity_provider=azuread' -d 'target={target}'
```

Do not implement your own token caching or OAuth flow manually in the app. See `/auth-overview`.

## When this points elsewhere

- JWKS unreachable due to a pod/network problem → [pod-diagnose.md](./pod-diagnose.md)
- Fix discipline (reproducing 401/403 in `testApplication { }`) → `/diagnosing-bugs`
