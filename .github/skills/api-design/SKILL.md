---
name: api-design
description: "Use when this backend's HTTP API contract changes shape rather than its implementation: versioning a route, breaking an existing contract, deprecating an endpoint, finding and notifying consuming teams, or publishing the API in Nav's API catalogue. Triggers: 'breaking change' / 'brytende endring', 'version the API' / 'versjonere API-et', 'who calls this endpoint' / 'hvem kaller dette endepunktet', 'deprecate the route' / 'deprekere ruta'. Route code lives in /kotlin-ktor, token validation in /auth-overview, accessPolicy in /nais-manifest."
---

# API design — contract changes

Nav-specific rules for changing the HTTP API contract in this Ktor repository.
Generic REST design (verbs, status codes, resource naming, pagination as a
concept) is not covered — bring that from your own repertoire. This skill covers
only what is specific to Nav and to this repository.

## Ktor patterns, not Spring patterns

This is a Ktor backend. Propose Ktor constructs — `routing { }`,
`Authentication` with `authenticate("...")`, `StatusPages`,
`ContentNegotiation`. Do not carry over Spring idioms (`@RestController`,
`@RequestMapping`, `ResponseEntity`, Spring Kafka) or a Spring-shaped error
contract (RFC 7807 `ProblemDetail`). Nav-wide API guidance is usually written
for Spring Boot services; translate the intent, not the code.

## High login level: check the `acr` claim

An API that requires a high login level must check the `acr` claim
(`idporten-loa-high`; older tokens carry `Level4`) — an authenticated caller is
not automatically an adequately authenticated caller. Everything else about
validation (issuer, audience, `pid`, `azp`, the library setup) belongs to
`/auth-overview`.

## Register the API in the API catalogue

Register the API at [apikatalog.nav.no](https://apikatalog.nav.no) so other Nav
teams can find it. Especially important when the API may be useful beyond its
current consumers.

## Breaking changes are a coordination problem

A breaking change to an API other Nav teams consume is not just a technical
change. Run it as a coordinated release:

1. **Identify the consumers** — `accessPolicy.inbound` in the Nais manifest
   gives the permitted list; actual traffic (logs, metrics) gives the real one.
   Use both, they differ.
2. **Notify the consuming teams explicitly** — their Slack channel, not a
   changelog entry. Do not assume anyone reads release notes.
3. **Agree a transition window** — typically 1–3 months with both versions live.
4. **Version the path** — a new route prefix (`/api/v1/` → `/api/v2/`), keeping
   the old routes until the window closes.
5. **Deprecate before removing** — mark the old version with `Deprecation` and
   `Sunset` response headers so consumers see the deadline in traffic.
6. **Remove the old version** only after the window has passed and traffic on it
   has stopped.

Use `/architecture-review` for the cross-team and platform consequences.

### What counts as breaking

- Removing or renaming a field, endpoint or route prefix; tightening validation.
- Changing the shape of the error response contract: renaming or removing a
  field, or narrowing the set of error codes a consumer may already branch on.
  Consumers parse error responses too, so the error body is part of the
  contract just as much as the success body.

### What is safe

- Adding a response field, an optional request field, or a new endpoint.
- Adding a new error code value — provided consumers deserialize error
  responses defensively.

Safe changes still get documented; they just do not need a coordinated release.

## Boundaries

### Ask first

- Any breaking change to a contract another team consumes.
- Removing a consumer from `accessPolicy.inbound`.
- Exposing the API outside the cluster.

### Never

- Ship a breaking change silently, without notifying consumers.
- Put personal data (national identity numbers, names) in URLs or query
  parameters — take the identity from the token claim instead.
