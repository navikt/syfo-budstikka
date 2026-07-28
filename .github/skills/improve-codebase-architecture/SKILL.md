---
name: improve-codebase-architecture
description: "Find deepening opportunities in the codebase. Use when tightly coupled modules need consolidation or code needs better testability and AI navigability."
---

# Improve codebase architecture

Find architectural friction and propose **deepening opportunities**: refactorings
that turn shallow modules into deep ones. Recommend Grill with docs to challenge
the selected candidate, or Nav architecture review to formalize a hard choice
as an ADR. Wait for the user to select either manual workflow; a literal skill
name is not invocation authority.

Start from `docs/context.md`, `docs/glossary.md`, and relevant ADRs. Use the
**module**, **interface**, **implementation**, **depth**, **seam**, **adapter**,
**locality**, and **leverage** vocabulary consistently.
Do not reopen ADRs without real friction. Apply the deletion test: if deleting a
module concentrates complexity, it was shallow; if it spreads complexity, it was
valuable.

## 1. Explore

Choose where improvement would be used before scanning. Follow a direction
named by the user; otherwise inspect roughly the last 20 commit subjects and
bias the scan toward actively changed paths. A deepening opportunity in code
the team does not expect to touch has little leverage.

Read domain docs and ADRs, then inspect the selected area organically. Typical
candidates in a Ktor backend include thin forwarding chains, DTO mappers
extracted only for testability, wrappers that leak token/retry/error detail,
scattered Kafka handling, database details leaking from repositories, and
modules requiring half the Ktor stack to test. Apply the deletion test to each
suspected shallow module.

## 2. Present an HTML report

Write one standalone report in OS temporary storage, for example
`$TMPDIR/architecture-review-<timestamp>.html`, open it for the user, and state
the absolute path. Each candidate contains files, one-sentence problem and
solution, vocabulary-based benefits, before/after diagram, and recommendation
strength. Finish with the recommended first candidate.

Use glossary terms for domain concepts and the deep-module vocabulary for
structure. Mark an ADR conflict only when real friction justifies reopening it.
See [references/html-report.md](references/html-report.md) for the scaffold,
diagram patterns, and style. Do not propose concrete interfaces yet; ask which
candidate the user wants to explore.

## 3. Grill the chosen candidate

Recommend Grill with docs for constraints, dependencies, deep-module shape,
seam contents, and surviving tests; wait for explicit selection before that
manual workflow writes documentation. Explore at least two interface
alternatives serially in the main context. Add or sharpen durable glossary
language only as it is agreed. Offer an ADR only for a durable reason future
reviewers need.

## 4. Connect to delivery

Lock the chosen approach in the brief, record hard durable choices in ADRs, and
update `docs/context.md` only when its model/index changes. Break the work into
safe task-scoped slices. When tracker-backed slices add value, recommend the
explicit issue-management workflow after the user confirms the issue structure.
Define proof such as tests
through one interface and a seam justified by two adapters. Kokk implements one
slice; the Grillmester route retains its risk-based Inspector policy.
