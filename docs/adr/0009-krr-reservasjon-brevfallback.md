# ADR 0009 — KRR reservation as decision gate with `brevFallback`

- Status: Decided (operationalizes B2/B7/B8; first part of ungrilled Auth & ACL area 5, B62)
- Date: 2026-07-22
- Related: B2/B7/B8/B10/B25/B28/B55, ADR 0001, issue #22 (epic #15), `/auth-overview`

## Context

`BrukervarselCreate` already has optional SMS/email `externalVarsling` and
`brevFallback`. The missing decision gate uses KRR (`digdir-krr-proxy`) contact/
reservation state. Three choices are authentication, fallback trigger, and
location/delivery of the rule.

The legacy esyfovarsel snapshot kept KRR and sickness-absence eligibility
separate: `AccessControlService` used only KRR `kanVarsles` for external
SMS/email, while `syfosmregister` guarded a specific meeting-needs notification
to a nearest leader/employer. Budstikka intentionally adopts only the neutral
KRR contact/reservation gate. Sickness-absence eligibility is domain knowledge
and remains producer-owned under B1/B2 and ADR 0001; its absence here is a
boundary decision, not a missing dependency.

## Decision

1. **KRR uses Azure AD M2M, not TokenX.** Budstikka is a Kafka consumer without
   inbound user context, so it reuses `TexasTokenProvider`
   (`identity_provider = entra_id`) and KRR scope like PDL/document distribution.
2. **Fallback means `kanVarsles == false`.** It covers both digital reservation
   and missing verified digital contact: external delivery cannot arrive, so send
   a letter when `brevFallback` exists. `ReservationLookup.isReserved(ident)`
   exposes this domain-blind semantic and hides KRR contract.
3. **`ReservationGate` is a `DecisionRule`.** It self-selects
   `BrukervarselCreate` and queries KRR only when
   `externalVarsling != null || brevFallback != null`. When `isReserved`—the
   domain-neutral name for `kanVarsles == false`—is true, it removes external
   notification with `copy(externalVarsling = null)`. The in-app Brukervarsel
   remains visible on Min side; only SMS/email is suppressed. When present, it
   also creates a BREV `DeliveryDraft` using existing
   `BrevCreate(personIdentifier, journalpostId, distributionType)`, delivery row,
   `BrevChannelHandler`, and dokdist. No channel/table/handler is added.

Rules are `[DeathGate, ReservationGate]`: death short-circuits before letter.
Transient KRR failure throws from `resolve` for shell backoff, never silently
means non-reserved. Reservation is resolved into deliveries, not persisted as a
column.

## Consequences

Add digdir-krr-proxy outbound access, KRR scope, `KRR_URL`, and `KRR_SCOPE` in
the client PR. FNR is sent to KRR but never logged; KRR failures log status only.
DPIA/processing record must cover this new personal-data source before production.
Verify endpoint path, `team-rocket` namespace, and scope in dev before production.

Rejected: TokenX/OBO without user context; `reservert` alone (misses no-contact
people); a new letter channel; and implicit tms reservation handling.
