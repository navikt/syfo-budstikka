# Migration: esyfovarsel → syfo-budstikka

Move notification production from `navikt/esyfovarsel` to syfo-budstikka without
duplicate notifications, lost closures, or ghost data. Decisions B34–B37 are
recorded in `decisions.md`. The basis is a source-code mapping of esyfovarsel
(2026-07, HEAD `3ac54de`); see “Type inventory”.

## Core challenge

Notifications have a **lifetime**. Creating new notifications in Budstikka is
easy; the difficult cases are in-flight notifications that straddle cutover. The
downstream notification identity (`eksternReferanse`/`grupperingsid`) was minted
by esyfovarsel and often exists *only* in the esyfovarsel database
(`UUID.randomUUID()`). Budstikka therefore cannot close it.

**Three rejected alternatives:**

- **Facade** (Budstikka forwards closes to esyfovarsel `varselbus`): unnecessary
  for self-expiring types and *insufficient* for dialogmøte (a shared SAK with a
  random `grupperingsid` cannot be adopted midway).
- **Reference continuity** (Budstikka derives the same reference): impossible —
  references are mostly `UUID.randomUUID()`; it would also couple Budstikka to a legacy schema.
- **State handover** (import esyfovarsel open notifications): complex, risky
  reference reconciliation.

## Backbone (B34): sticky ownership, producer-routed per process

The system that performed **CREATE owns the entire lifetime**. The producer routes
FERDIGSTILL and follow-up events to the **same** system. The migration unit is a
**process/grouping** (a whole dialogmøte case, one møtebehov round, or one letter),
not an individual notification.

- **Everything new → new solution:** The producer flips its *own* CREATE output to Budstikka.
- **Prevent duplicate notifications:** Every process goes to exactly one system
  (no duplicate send; systems do not share a deduplication store).
- **No race:** CREATE and FERDIGSTILL share a partition key (recipient,
  B5/B24/B32) → ordered on the same partition, so a close cannot be processed
  before its own CREATE.

## Hybrid strategy (B35): split by notification nature

| Nature | Strategy | Closure mechanism |
|---|---|---|
| Self-expiring, informational, or fire-and-forget | **Hard switch per type + let expire** | None |
| True task/case without time cap plus random reference | **Process-routed drain-close** (column, B37) | Yes |
| Boundary case (oppfølgingsplan, AG case about 4 weeks) | Hard switch + accept about four-week stale window | No, deliberate simplification |

**Why dialogmøte MUST use process routing** — not because of expiry time
(microfrontend expires at meeting date and AG case at `hardDeleteDate` plus four
months), but because dialogmøte is a **state machine** over events sharing one
case (INNKALT → NYTT_TID_STED → AVLYST/REFERAT). A follow-up after cutover that
reaches Budstikka cannot update the esyfovarsel case → a **ghost meeting**: a
moved or cancelled meeting remains active until expiry. Force-closing the old
case early removes the reminder *before* the meeting, which is also a regression.
Therefore, a whole dialogmøte stays in esyfovarsel until closure; only *new*
dialogmøter start in Budstikka.

## Applications that need the column (B37)

Criterion: **event-driven closure without time expiry plus random reference.**
Only 2 of 12 producers clearly meet it; 2 boundary cases accept a stale window;
8 need nothing.

| Producer | Type(s) | Closure | Column? |
|---|---|---|---|
| **isdialogmote** | dialogmøte family (SAK + INNKALT-OPPGAVE + microfrontend + AG case) | event-driven, shared case, random UUID | **YES (heavy — flag on the case)** |
| **syfomotebehov** | SM/NL_DIALOGMOTE_SVAR_MOTEBEHOV (OPPGAVE) | event-driven, random UUID, no cap | **YES (light — flag on the møtebehov round)** |
| isoppfolgingsplan | NL_OPPFOLGINGSPLAN_FORESPORSEL (AG case, 4 weeks) | close, bounded to 4 weeks | No — accept stale state |
| ~~syfooppfolgingsplanservice~~ | NL/SM_OPPFOLGINGSPLAN_SENDT_TIL_GODKJENNING | **DEPRECATED — turned off after summer** | Out of scope; not migrated |
| aktivitetskrav-backend | SM_AKTIVITETSPLIKT | auto-close job 2–14d, *deterministic* UUID | No — self-closes |
| meroppfolging-backend | SM_MER_VEILEDNING | time expiry 105d | No |
| ismeroppfolging | SM_KARTLEGGINGSSPORSMAL | time expiry 30d | No |
| isarbeidsuforhet / isfrisktilarbeid / ismanglendemedvirkning | BREV types | fire-and-forget | No |
| syfo-oppfolgingsplan-backend | SM_OPPFOLGINGSPLAN_OPPRETTET | BESKJED, no close | No |
| syfo-dokumentporten | AG_VARSEL_ALTINN_RESSURS | FERDIGSTILL *ignored*, 4mo TTL | No |

Note: OPPGAVEr *with* a time cap (mer_veiledning, kartlegging, and
aktivitetsplikt) close themselves, so need no column. Microfrontend self-expires
at `synligTom`; only the dialogmøte microfrontend is complex, and is covered by
isdialogmote routing the whole case.

## Column mechanism (B37)

The `varselsystem ∈ {ESYFOVARSEL, BUDSTIKKA}` flag lives in the **producer's own
process table**, not Budstikka. Set it at CREATE and read it at FERDIGSTILL.

- **isdialogmote:** The flag belongs to the **dialogmøte/case**, not an individual
  notification, so the whole follow-up chain is consistently routed to one system.
- **syfomotebehov:** The flag belongs on the **møtebehov round**.
- Prefer this to a date rule because it survives rollback: a process started in
  the Budstikka window remains marked BUDSTIKKA even if the type is temporarily rolled back.

### Concrete example: syfomotebehov

One-time change: (1) produce the Budstikka contract (B22) to
`team-esyfo.budstikka.v1`; (2) add a `varselsystem` flag on the møtebehov round,
set at CREATE; (3) route FERDIGSTILL by the flag; (4) retain old `varselbus`
output until old rounds have drained.

| After cutover T | Routing | Result |
|---|---|---|
| New møtebehov B (CREATE) | → Budstikka (`BUDSTIKKA`) | delivery or deliveries, fan-out to every channel (B13/B14) |
| User responds to B (FERDIGSTILL) | `flag=BUDSTIKKA` → Budstikka | Producer sends one typed Inactivate per channel; each matches `(reference, recipient_id, channel)` (B19–B21) |
| User responds to A (pre-T, FERDIGSTILL) | `flag=ESYFOVARSEL` → esyfovarsel (`varselbus`, `ferdigstill=true`) | esyfovarsel looks up its own random UUID and closes |

## Sequencing (B36): per (type × producer), never big bang

Big bang concentrates risk into one day. Prove the pipeline on low-risk types first.

| Step | Types / producers | Why |
|---|---|---|
| **1** | BREV types: isarbeidsuforhet, ismanglendemedvirkning, isfrisktilarbeid | Fire-and-forget, one channel, no straddle, deterministic `journalpost.uuid`. Proves inbox → decision → outbox → dokdist. |
| 2 | BESKJED without close: syfo-oppfolgingsplan-backend, `*_TILBAKEMELDING` | No closure |
| 3 | Time-based multi-channel: meroppfolging-backend, ismeroppfolging | Self-expiry, 30–105d |
| 4 | aktivitetskrav-backend | Budstikka must take over the auto-close job |
| **5 (last)** | Dialogmøte family + AG-Altinn: **isdialogmote** (plus syfomotebehov, syfo-dokumentporten) | Shared SAK state machine; migrate at case boundary, never midway. The large task. |

## Drain and decommission

Per type, once the producer has zero in-flight *pre-cutover* processes, remove
old output and esyfovarsel stops handling that type. Drain window:

- Fire-and-forget: immediate.
- Time-based: natural expiry, 30 days to 15 weeks.
- Dialogmøte: bounded by **meeting dates** (typically weeks); 4-month
  `hardDelete` is only an outer safety net for cases never closed.

Decommission esyfovarsel completely once *every* type has zero in-flight work.

## Type inventory (source: navikt/esyfovarsel)

`HendelseType` (25 types) in `EsyfovarselHendelse.kt:88-115`; prefixes
`SM_`/`NL_`/`AG_` = sykmeldt/nearest leader/employer. Incoming topic:
`team-esyfo.varselbus`.

### Legacy output map

| esyfovarsel channel | Technical surface | Notes |
|---|---|---|
| `BRUKERNOTIFIKASJON` | `min-side.aapen-brukervarsel-v1` | Kafka; tms notification builder |
| `DINE_SYKMELDTE` | `team-esyfo.dinesykmeldte-hendelser-v2` | Kafka |
| `DITT_SYKEFRAVAER` | `flex.ditt-sykefravaer-melding` | Kafka |
| `ARBEIDSGIVERNOTIFIKASJON` | `notifikasjon-produsent-api` plus Altinn | GraphQL plus Altinn |
| `BREV` | `dokdistfordeling` | HTTP; producer supplies `journalpostId`, and Budstikka does not create the PDF |
| `MIN_SIDE_MICROFRONTEND` | `min-side.aapen-microfrontend-v1` | Kafka |

BREV/AG-Altinn references use deterministic
`journalpost.uuid`/`eksternReferanseId` from the producer; the rest use
`UUID.randomUUID()` only in the esyfovarsel database. No active future-dated
scheduler queue exists (`PlanlagtVarsel` is legacy).

### Legacy eligibility dependencies

The source snapshot separates three concerns that the old context survey
conflated:

- `AccessControlService` reads only KRR `kanVarsles` to enable or suppress
  external SMS/email. It does not query syfosmregister or select letter
  fallback.
- `SykmeldingService` queries `syfosmregister` only for a domain-specific
  `MotebehovVarselService` guard: nearest-leader/employer notifications are
  suppressed when the sickness absence was not sent to that employer. Under B1
  and ADR 0001, this eligibility remains producer-owned and is not a Budstikka
  dependency.
- The `istilgangskontroll` client concerns OBO veileder access, not notification
  channel selection. Budstikka has no inbound user context and does not inherit
  that dependency without a separate concrete use case.

## Open points

- **syfooppfolgingsplanservice is deprecated** and is turned off after summer
  (2026), so is outside migration scope. **Clarified:** The function disappears
  **entirely** and is not moved; Budstikka never builds
  `*_OPPFOLGINGSPLAN_SENDT_TIL_GODKJENNING`, and these 2 types die with the app.
- **isdialogmote case boundary:** Detail exactly how the flag belongs on the
  dialogmøte/case and how the whole follow-up chain
  (INNKALT/NYTT_TID_STED/AVLYST/REFERAT) is routed consistently when step 5 begins.
- **aktivitetsplikt auto-close job:** Budstikka must reproduce the 2–14-day
  auto-close for notifications *it* creates; the esyfovarsel job drains in parallel.
- **Microfrontend expiry:** Budstikka owns its own `synligTom`-based closure
  (compare esyfovarsel `closeExpiredMicrofrontendsJob`).
