---
name: improve-codebase-architecture
description: "Use when the user wants to improve the architecture, find refactoring opportunities, make shallow modules deeper, consolidate tightly coupled modules, or make the code more testable and easier to navigate for both people and AI. Also when someone says 'improve the architecture', 'find refactoring opportunities', 'this code is messy/hard to test', 'too many thin layers', or 'the route → service → client chains are hard to follow'."
---

# improve-codebase-architecture

Uncover architectural friction in this repository and propose **deepening opportunities** — refactorings that make shallow modules deep. The goal is testability, and that both humans and AI can navigate the code easily.

**Role:** this _finds_ candidates (discovery). Design the interface for a chosen
candidate inline with two genuinely different alternatives and interrogate the
choice with `/grilling`. When lasting concepts or decisions ought to be
documented, recommend the documented route and wait for the user's choice. Use
`/architecture-review` for NAV review and `/domain-modeling` after the
documented route has been chosen.

The skill is **informed by** the domain model and settled decisions, and builds on a shared architecture vocabulary:

- `docs/glossary.md` names good seams in the domain; relevant topic
  documents describe maintained detail. Interpret ADR status via
  `docs/agents/domain.md`, and do not re-litigate binding decisions without reason.
- This is @grillmester's discovery phase: findings from here feed into natural
  grilling (`/grilling`), the active plan and verification.

## Vocabulary

Use the deep-module vocabulary precisely: a **module** hides an **implementation** behind a small **interface**; **depth** is the amount of complexity the interface hides. A **seam** is the place where the module can be separated from an **adapter**. **Locality** keeps related knowledge together, and **leverage** is how much complexity a single interface carries. Do not drift into "component", "service", "layer" or "API" when these more precise words fit.

**The deletion test** (the operational tool for discovery): would deleting the module *concentrate* complexity (good — it was shallow) or merely move it (then it was real)? A "yes, it concentrates" is the signal you are hunting for.

## Process

### 1. Explore

Follow the narrow loading order in `docs/agents/domain.md`: read the glossary when the domain language is relevant, and only the topic documents and ADRs that touch the area.

Then walk the codebase organically — do not follow rigid heuristics. Note where you experience friction. In a Ktor backend, deepening opportunities typically look like this:

- **Thin layers in a chain:** `Route { } → Service → Repository → client` where each link does little more than forward. Merge into one deep module.
- **DTO mappers extracted purely for testability:** `toDto()` / `fromDb()` functions that are pure, but where the real bugs sit in how they are called (no locality).
- **Shallow client wrappers:** an `HttpClient` call wrapped in a class that hides nothing — the TokenX/Azure AD token, retry and error contract leak out to the call site.
- **Scattered Kafka logic:** consumer, deserialization, idempotency/replay handling and business logic spread across several modules without a single seam.
- **Leaking database layer:** SQL/`DataSource`/Flyway details seeping out of the repository module.
- **Hard to test through the interface:** modules that require spinning up half the Ktor stack to be tested — a sign the seam is in the wrong place.

Apply the deletion test to anything you suspect is shallow.

### 2. Present the candidates as an HTML report

Write a self-contained HTML file to the OS temp directory so that nothing ends up in the repository. Resolve the temp directory from `$TMPDIR` with `/tmp` as fallback, and write to `<tmpdir>/architecture-review-<timestamp>.html`. Open it for the user (`open <path>` on macOS, `xdg-open <path>` on Linux) and state the absolute path.

Each candidate gets a card with: **Files**, **Problem** (one sentence), **Solution** (one sentence), **Benefits** (bullet list in the vocabulary — locality/leverage/test surface), **Before/after diagram**, and **Recommendation strength** (`Strong`, `Worth exploring`, `Speculative`). Close with a **Top recommendation**: which one you would take first and why.

Use **`docs/glossary.md` vocabulary for the domain** and the architecture vocabulary above for the structure. If the concept is called "Sykmelding-inntak" in the glossary, talk about "the Sykmelding-inntak module" — not "SykmeldingHandler" and not "the Sykmelding service".

**ADR conflict:** if a candidate contradicts an existing ADR, raise it only when the friction is real enough to justify reopening the decision. Mark it clearly on the card (yellow callout: _"contradicts ADR-0007 — but worth reopening because…"_). Do not list every theoretical refactoring an ADR forbids.

See [HTML-REPORT.md](HTML-REPORT.md) for the full HTML scaffold, diagram patterns and style guide.

**Do not** propose concrete interfaces yet. Once the file is written, ask the user: "Which of these do you want to explore?"

### 3. Grilling loop

Once the user has chosen a candidate, run `/grilling` to walk down the decision
tree together with them — constraints, dependencies, the shape of the deepened
module, what sits behind the seam, which tests survive. This is @grillmester
phases 1–2.

When clarified concepts or qualifying, lasting decisions ought to be written to
`docs/`, recommend `/grill-with-docs`, explain why and wait for the user's
choice. Before a documented route is chosen, keep the results in the
conversation and the active task. Use a task-local `.grill/` only when the
calling workflow has explicitly chosen it.

After a documented route has been chosen, documentation happens **continuously**
as decisions fall into place:

- **Naming a deepened module after a concept that is not in `docs/glossary.md`?** Add the term there (use `/domain-modeling`). Create the file lazily if it is missing.
- **Sharpening a vague term along the way?** Update `docs/glossary.md` right away.
- **Does the user reject the candidate for a load-bearing reason?** Consider an
  ADR only when the decision is hard to reverse, surprising without context and
  the result of a real trade-off. Skip transient ("not worth it right now") and
  self-evident reasons. Use `/architecture-review` if NAV-specific consequences
  need assessing, and `/domain-modeling` for the ADR itself.
- **Want to explore alternative interfaces for the deepened module?** Design two genuinely different alternatives sequentially, inline, before comparing them. Use subagents only for compact, read-only divergent exploration, never for parallel writing.

### 4. Connect to the phase loop

Once the chosen deepening has been thoroughly grilled:

- Write the task scope to the issue/plan. After a documented route has been
  chosen, `/domain-modeling` writes new concepts and qualifying decisions;
  maintained detail goes to the relevant topic document.
- Break the deepening down into a safe, incremental refactoring plan in the
  active task (optionally on to `/to-issues` for grabbable slices).
- Define what proves the deepening succeeded (tests through a single
  interface, the seam confirmed by two adapters), and return that to the calling
  workflow.
