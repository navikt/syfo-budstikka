---
name: api-design
description: "Use when this backend's HTTP API contract changes shape rather than its implementation: versioning a route, breaking an existing contract, deprecating an endpoint, finding and notifying consuming teams, or publishing the API in Nav's API catalogue. Triggers: 'breaking change' / 'brytende endring', 'version the API' / 'versjonere API-et', 'who calls this endpoint' / 'hvem kaller dette endepunktet', 'deprecate the route' / 'deprekere ruta'. Route code lives in /kotlin-ktor, token validation in /auth-overview, accessPolicy in /nais-manifest."
---

# API design — contract changes

This repository's published contract surface is not an HTTP API — it is the
`:kontrakt` Kafka library. The service itself exposes only `/internal` probes
(`src/main/kotlin/no/nav/budstikka/api/InternalApi.kt`), and `accessPolicy` in
`nais/nais-dev.yaml`/`nais-prod.yaml` has no inbound rules. Route "breaking
change", versioning and deprecation questions to the kontrakt surface first
(next section); the HTTP sections after it are design guidance for a future
inbound surface, not descriptions of existing code. Generic REST design (verbs,
status codes, resource naming, pagination as a concept) is not covered — bring
that from your own repertoire.

## The actual contract surface: the `:kontrakt` library

- **Versioning, breaking-change and release rules** live in
  `docs/sende-varsler.md` ("Versjonering og utvikling"): semantic versioning,
  a breaking producer-API change in `0.x` requires a new minor version with a
  release note and migration, publishing is triggered only by an authorized
  `kontrakt/vX.Y.Z` tag from `main`, and published package versions are
  immutable.
- **Binary compatibility is gated by japicmp**: the
  `checkPublishedContractCompatibility` task in
  `build-logic/src/main/kotlin/budstikka.kontrakt-compatibility.gradle.kts`
  compares the built jar against the published baseline resolved by
  `scripts/verify-contract-baseline.sh`, wired into
  `.github/workflows/publish-kontrakt.yml` and `ci-reusable.yml`.

A contract change that does not pass the japicmp baseline is a breaking change
regardless of intent — follow `docs/sende-varsler.md`, do not argue with the
gate.

## If an HTTP API is added: Ktor patterns, not Spring patterns

This is a Ktor backend, so a future HTTP surface would use Ktor constructs —
`routing { }`, `Authentication` with `authenticate("...")`, `StatusPages`,
`ContentNegotiation`. Note that none of these plugins beyond routing are on the
classpath today (`build.gradle.kts` pulls only ktor-server
core/netty/call-id/di/micrometer): adding an HTTP contract means adding the
dependencies and the auth design, not just routes. Do not carry over Spring
idioms (`@RestController`, `@RequestMapping`, `ResponseEntity`, Spring Kafka)
or a Spring-shaped error contract (RFC 7807 `ProblemDetail`). Nav-wide API
guidance is usually written for Spring Boot services; translate the intent, not
the code.

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

(For today's actual contract — the `:kontrakt` library — the versioning and
breaking-change rules live in `docs/sende-varsler.md`; the steps below describe
a future HTTP API.) A breaking change to an API other Nav teams consume is not
just a technical change. Run it as a coordinated release:

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
