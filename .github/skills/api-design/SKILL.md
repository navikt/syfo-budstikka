---
name: api-design
description: "Design or change Ktor APIs and contracts. Use when work touches endpoints, consumer access, token validation, versioning, or breaking API changes."
---

# API design — Nav conventions

Use this for Nav-specific API contracts in this repository; apply the team's
established REST and HTTP conventions for the rest. It normally belongs in the
design phase and again when implementing a route.

## `accessPolicy.inbound`

Every NAIS-exposed API explicitly lists its callers; there is no implicit
"every Nav application" access.

```yaml
spec:
  accessPolicy:
    inbound:
      rules:
        - application: saksbehandling-frontend
          namespace: team-vedtak
          cluster: prod-gcp
```

- Do not leave `inbound` empty for an internal API unless denying every caller is
  intentional, and never use `*` without explicit justification and security
  review.
- Coordinate before adding a consumer: it also needs an outbound rule. Remove
  unused consumers during review at least quarterly.

## Server-side authentication

Validate a token before business logic with Ktor `Authentication` and an
`authenticate("...")` block around exposed routes; never add ad-hoc validation
to each handler. See `/auth-overview` for implementation. Use
`no.nav.security:token-validation-ktor-v3` (`tokenValidationSupport`), not a
raw `jwt()` plugin, and validate `azp` against
`AZURE_APP_PRE_AUTHORIZED_APPS` for M2M callers.

The contract must cover the correct environment-specific issuer, `aud`, JWKS
signature, expiry (`exp`/`nbf`) and, where applicable, `pid` and `acr`. `pid`
is authoritative identity, never a request-body or path value. Never log a full
token. Keep intentional public `/internal/*` probes outside authentication.

## Versioning and consumer coordination

A breaking change for another Nav team is a coordination decision and belongs in
an ADR. Before release: identify callers from `accessPolicy.inbound` and traffic,
notify them explicitly, agree a transition window, serve a versioned replacement
route, deprecate the old route, and record the decision in `docs/adr/`. Update
`docs/glossary.md`, and update `docs/context.md` only when its current mental
model or index changes.

Adding response fields, optional request fields with sensible defaults, endpoints,
or a defensively consumed `ErrorType` is normally compatible. Renaming/removing
an `ErrorType` or changing the `ApiError` shape is breaking too; see
`/kotlin-ktor` and `../kotlin-ktor/references/error-handling.md`.

Register discoverable APIs in [apikatalog.nav.no](https://apikatalog.nav.no).
Use the documentation format the team already uses; consumer comprehension,
not format, is the outcome.

## Delivery boundary

For a new contract, recommend the manual Grill with docs workflow and wait for
the user to select it; never invoke it automatically. Lock security-sensitive
or breaking choices in the brief/ADR before implementation. The brief and PR
must show acceptance and verification; R3/R4 slices receive
`grill-inspektor` review.

### Always

- Use explicit named `accessPolicy.inbound` callers.
- Validate issuer, audience, signature and relevant identity claims in
  `authenticate` for exposed APIs.
- Coordinate breaking changes before release.
- Keep FNR and names out of URLs and query parameters; use token `pid` instead.

### Ask first

- Removing an inbound consumer, breaking a contract, or exposing an API outside
  its cluster.

### Never

- Use empty/wildcard inbound policy without security review, trust identity from
  body/path, ship a silent breaking change, log tokens/PII, or leave an exposed
  route unauthenticated unless it is intentionally open.
