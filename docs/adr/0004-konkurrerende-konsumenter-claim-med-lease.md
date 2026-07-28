# ADR 0004 — Competing inbox consumers: claim with `SKIP LOCKED` and lease

- Status: Decided (issue #56, decision worker)
- Date: 2026-07-10
- Related: ADR 0002, ADR 0003, `docs/datamodell.md`, decision B28

## Context

`InboxMessageWorker` must run in multiple replicas. Without coordination they
poll the same `RECEIVED` rows, duplicate PDL/KRR lookups, and can duplicate
delivery rows. Lookup cannot occur in a database transaction because a pooled
connection must not span network I/O. Row locks therefore cannot cover the full
lifecycle.

Holding `FOR UPDATE` through processing was rejected for long transactions and
pool exhaustion. Optimistic terminal CAS alone was rejected because replicas
would duplicate external lookup. Leader election was rejected because true
competing consumers are required.

## Decision

Use transactional-inbox claim with `FOR UPDATE SKIP LOCKED` plus visibility-timeout
lease and terminal compare-and-set (CAS):

1. Claim a bounded batch in one transaction with this selection contract:
   `WHERE state='RECEIVED' OR (state='CLAIMED' AND next_attempt_time <= now())`
   plus `ORDER BY received_at, event_id LIMIT :batch FOR UPDATE SKIP LOCKED`.
   In that same transaction, set the selected rows to `CLAIMED`, increment
   `attempt`, and set `next_attempt_time = now() + lease`. `SKIP LOCKED` gives
   each replica a disjoint non-blocking batch.
2. Perform enrichment outside a transaction.
3. For each message, transactionally create delivery rows and terminal status.
   Run `UPDATE ... SET state='PROCESSED' WHERE event_id=? AND state='CLAIMED'`
   first; only CAS winner (`rowcount=1`) writes deliveries.
4. No reaper worker: expired leases make dead-worker rows claimable again.
5. Enrichment is at-least-once; delivery is exactly-once through terminal CAS.
6. `workers.inboxMessage.leaseSeconds` defaults to five minutes and `batchSize`
   is 25. Lease must cover batch drain; too short causes duplicate lookup, too
   long only delays recovery.

No migration is needed: `state` is unconstrained `TEXT`, and existing
`(state, next_attempt_time)` index supports claim.

## Consequences

Replicas poll safely with disjoint batches; recovery is lease-driven; external
lookup occurs once on the happy path; no schema migration is needed. A dead
worker delays one lease before recovery; `CLAIMED` is a new operational
non-terminal state. There is no heartbeat/lease extension yet, so work longer
than lease can be reclaimed; add it only when both long margin and fast recovery
are required. Claim `ORDER BY received_at` index tuning is deferred.
