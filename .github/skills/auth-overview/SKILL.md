---
name: auth-overview
description: "Configure or diagnose syfo-budstikka authentication. Use when work touches Texas or Entra tokens, inbound TokenX or Azure AD validation, accessPolicy, audience, issuer, scopes, or 401/403 responses."
---

# Authentication in syfo-budstikka

First distinguish the current state from a proposed design. Read code,
`application.conf`, and `nais/nais-*.yaml` before choosing a mechanism.

## Current state

- The application has no inbound business API or Ktor `Authentication` plugin;
  only internal health and metrics routes.
- NAIS enables `azure.application`.
- `TexasTokenProvider` obtains Entra ID machine tokens from
  `NAIS_TOKEN_ENDPOINT` with `identityProvider = "entra_id"`.
- PDL, KRR, and document distribution use outbound scopes from configuration.
- `accessPolicy.outbound` permits the actual downstream services.

Do not describe TokenX/Azure inbound validation as installed until both code and
manifest prove it.

## Choose a new flow

Clarify caller, whether end-user context must propagate, the authorizing claim,
downstream target/scope, and the matching `accessPolicy` edge.

| Flow | Mechanism |
|---|---|
| Outbound M2M without a user | Entra ID machine token through the existing Texas provider |
| Inbound Nav M2M | Azure AD/Entra validation plus caller authorization |
| Inbound/outbound citizen context | TokenX validation and possibly OBO exchange |
| External system integration | Maskinporten when the contract requires it |

See [references/decision-tree.md](references/decision-tree.md) for edge cases
and [references/nais-configuration.md](references/nais-configuration.md) for manifest examples.
Verify a new mechanism against current NAIS documentation via
`/bounded-research`.

## Outbound tokens

Follow `infrastructure/auth/TokenProvider` and `TexasTokenProvider`. A new client
gets an explicit configured scope, obtains a token per request through the port,
and sends a bearer header without logging it. Texas owns caching and refresh;
do not build a parallel cache. Diagnose 401/403 by comparing provider,
scope/target, NAIS application, and outbound policy, never by logging JWTs.

## New inbound authentication

This is R4 work. Recommend the manual Grill with docs and Nav architecture
review workflows, explain their value, and wait for the user to select them;
never invoke either automatically. Run `/nav-security-review` as the
implementation-facing security check:

1. Select mechanism from caller and user context, not library preference.
2. Define issuer, audience, caller (`azp`/client), and authorization claim.
3. Choose Texas introspection or a Ktor validation library using current official
   guidance; this repository has no established inbound implementation to copy.
4. Install authentication in a Ktor plugin reached from `configureApplication()`.
5. Put business routes in `authenticate {}` and leave internal probes open.
6. Change dev/prod manifests, `accessPolicy.inbound`, config, and tests in one
   vertical slice.

## Security and verification

Validate signature, issuer, audience, and expiry; authorize caller/user
separately. `pid`, `NAVident`, tokens, and raw claims are sensitive. Configuration
and secrets come from NAIS/environment, and manifest plus code form one contract.
Changing audience, scope, caller rules, or production policy needs explicit human
confirmation.

Test valid/missing/expired tokens, wrong issuer/audience, and unauthorized callers
for inbound auth. For outbound auth, test Texas request, target/scope, bearer
propagation, and redacted failure logs with a mock; then run deterministic gates
and verify the 401/403 flow in dev without exposing token content.

Read [references/local-auth-mock.md](references/local-auth-mock.md) only when a
Ktor test or local run needs an OIDC issuer without real tokens.
