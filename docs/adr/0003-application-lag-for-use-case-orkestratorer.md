# ADR 0003 — Application layer for use-case orchestrators; adapters remain in infrastructure

- Status: Decided (issue #56, decision worker)
- Date: 2026-07-10
- Related: ADR 0002, `docs/teknologi.md`, decision B28 in `docs/decisions.md`

## Context

Issue #56 introduced `BackgroundLoop`/`Heartbeat` worker mechanics and
`InboxMessageWorker`, which polls `inbox_message`, decodes `Dispatch`, and
forwards it. The new `application` package was undocumented in the former
domain/infrastructure/api structure. The placement of worker mechanics, concrete
worker, and Kafka handler needed an explicit dependency rule.

## Decision

`application` is an explicit fourth use-case layer.

1. It may depend on `domain` and ports defined in `application.port`.
2. `domain` and `application` never depend on `infrastructure` or `bootstrap`.
3. `BackgroundLoop` and `Heartbeat` remain plumbing in `infrastructure/worker`.
4. Concrete workers such as `InboxMessageWorker` live in `application` and use
   only domain and ports.
5. `InboxMessageHandler` is a driving adapter in
   `infrastructure/kafka/consumer`: it handles `ConsumerRecord`, Kafka headers,
   offsets, dead letters, syntactic parse and hydrated inbox creation per ADR 0008,
   but no business logic.
6. A class naming transport (`ConsumerRecord`, `ApplicationCall`, HTTP request)
   is an infrastructure/api adapter; a class speaking only domain and ports is a
   use case; lifecycle plumbing is infrastructure.
7. DI wiring touching `application` belongs in `bootstrap`, the composition root.
8. Add a port only for multiple drivers, transport-free testable domain logic, or
   complex orchestration, never speculation.

This is ports-and-adapters / onion / clean architecture; this repository uses
the concrete term **ports and adapters**.

## Consequences

Future delivery/cleanup workers have a consistent home and enforceable dependency
direction. Worker mechanics and use cases are independently testable. The fourth
layer adds ceremony and may attract thin adapters, mitigated by placement rules.
Handler and worker own separate writer/processor halves of transactional inbox:
adapter persists idempotently; application decodes and decides.

## Alternatives considered

Putting `InboxMessageWorker` in infrastructure mixes orchestration and plumbing.
Keeping three layers has the same defect. Adding a transport-neutral handler port
now is speculative: one transport and no domain logic do not justify port/DTO/
mapping ceremony.
