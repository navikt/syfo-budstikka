---
name: nav-architecture-review
description: "Review a consequential Nav architecture choice across architecture, security, and platform perspectives, then prepare its ADR."
disable-model-invocation: true
---

# Nav architecture review — ADR and three perspectives

Run this manual workflow only inside an active Grillmester session. If another
outer role is active, stop and ask the user to switch with
`copilot --agent grillmester --model claude-opus-5 --context default`; do not
perform the workflow in that role.

Write Architecture Decision Records (ADRs) and perform substantial architecture
reviews for this repository. This skill covers Nav- and backend-specific concerns:
the NAIS/GCP platform, TokenX/Azure AD/Maskinporten, `accessPolicy`, DPIA, and
Nav architecture principles such as Team First and platform capability reuse.

Run only after the user explicitly selects this manual workflow. Present the
proposed decision and documentation change first, then obtain explicit
confirmation before creating or updating an ADR or another durable document.
Selection starts analysis; it does not pre-authorize the write.

**Role:** this skill formalises consequential choices as an ADR with Nav’s
three-perspective review. Find candidates with `/improve-codebase-architecture`,
interrogate the choice through the user-selected Grill with docs workflow, and
explore interface alternatives serially. `/domain-modeling` owns the base ADR
format; this skill adds Nav-specific sections.

## When it applies

Use during Grillmester’s grill/design phase. Consequential changes deserve an
ADR; small choices do not.

- New service, cross-team integration, storage layer, or Kafka/event contract.
- Change requiring another team’s `accessPolicy` update, or a new auth mechanism.
- New personal-data processing, potentially requiring DPIA or notice to Datatilsynet.
- Platform migration or technical-debt work that moves a seam.
- Deviation from Nav standard patterns or new technology in the stack.

Small choices, such as a library choice within the current stack or internal
refactoring without a new seam, need no ADR. Keep task-specific detail in the
brief or relevant technical document; update `docs/context.md` only when its
mental model or navigation changes.

## The stack is given

This repository is Kotlin and Ktor 3.x on Netty, Java 25, Gradle, NAIS, with
Postgres/Flyway and Kafka where needed. An ADR option must stay within that
stack unless changing the stack itself is the decision. Do not introduce Spring,
a new language, or a new runtime without that explicit decision.

## Three-perspective review

Evaluate the change from all three perspectives before concluding the ADR. Write
one to three lines per perspective: concern, risk, and recommendation.

1. **Architecture:** fit with Nav architecture, team autonomy, platform reuse,
   and accidental complexity. Use the deletion test for new layers: does a module
   concentrate complexity, or merely move it?
2. **Security:** data classification, auth mechanism, inbound/outbound
   `accessPolicy`, PII protection in logs/storage/transport, and DPIA need.
3. **Platform:** NAIS manifest changes, resource needs, Prometheus/Loki/Tempo,
   CI/CD, and on-prem or legacy dependencies.

Read [references/perspective-checklists.md](references/perspective-checklists.md)
for the full Nav-specific checklist. When changing an existing system, also
cover migration: backward compatibility, rollback, feature toggle, exit criteria,
and decommissioning.

## Alternatives and Architecture Advice Process

Document at least two alternatives plus “do nothing”. Nav’s Architecture Advice
Process is advisory, not approval-based: seek advice from affected parties, but
the team owns its decision. Identify consuming and producing teams early and
share a draft ADR before deciding.

## ADR format and storage

Use the canonical ADR base format from `/domain-modeling`
(`../domain-modeling/references/adr-format.md`: Status / Context / Decision /
Consequences / Alternatives considered). Store it as
`docs/adr/NNNN-<short-title>.md`, scanning `docs/adr/` for the highest number
and adding one. Add Nav-specific review as subsections in that ADR; see
[references/adr-template.md](references/adr-template.md). Keep it short: one
decision per ADR. Update status when decided; use “Superseded by NNNN-…” when
revising a decision.

## Workflow connection

- **Input:** grill/design findings and `/improve-codebase-architecture`. Read
  `docs/context.md` and `docs/glossary.md` so the ADR uses the domain’s own terms.
- **Output:** write the decision to `docs/adr/`; update `docs/context.md` only
  when the mental model or pointer map changes. Break concrete work into a
  task-scoped brief. If tracker-backed slices add value, recommend explicit
  issue-management after the user confirms the issue structure. Capture proof
  in the brief's `verification`.

## Boundaries

### Always

- Include at least two alternatives plus “do nothing”.
- Evaluate architecture, security, and platform.
- Document Nav-specific auth, data classification, `accessPolicy`, and NAIS considerations.
- End with concrete action items, owner, and deadline.

### Ask first

- ADRs affecting other teams’ services or contracts.
- Deviations from Nav standards such as NAIS, Kafkarator, or Cloud SQL.
- New technology, language, or platform component.
- New categories of personal data; assess DPIA and consult the privacy officer.

### Never

- Decide architecture without security and privacy assessment.
- Ignore platform consequences: resources, observability, or `accessPolicy`.
- Omit alternatives; there are always at least two.
- Put national IDs, other PII, or secrets in an ADR; refer to the correct source instead.
