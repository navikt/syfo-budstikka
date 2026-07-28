---
description: "Applies when documentation or repository agent contracts change; defines when context, ADRs, and the glossary are the correct source."
applyTo: "docs/**/*.md, AGENTS.md, .github/**/*.md"
---

# Using `docs/context.md`

`docs/context.md` is a short current mental model and index, not a work plan or
universal source of truth.

## Use `docs/context.md` when

- orienting during grilling, design, and planning;
- locating the correct topic document or ADR; or
- finding pointers to relevant durable documentation.

## Do not use `docs/context.md` when

- writing code comments that do not need design history;
- writing API error messages or runtime logs; or
- resolving a binding decision, which belongs in an ADR.

## Source precedence

1. `docs/adr/NNNN-*.md` for binding, hard-to-reverse decisions
2. the named entry in `docs/decisions.md` for a concrete `Bnn` reference;
   supersession must be explicit
3. `docs/glossary.md` for canonical domain terms
4. `docs/context.md` for the current mental model and pointers
5. the current GitHub issue or pull request and explicit task-scoped brief for
   active work

`docs/decisions.md` is active reference material but deliberately non-ambient.
Do not load the complete register unless the task genuinely spans it.

## Reference style

- Write paths such as `docs/context.md` directly in prose.
- Do not write `@docs/context.md` in code comments.
- An ADR reference in a code comment is appropriate when it explains a
  non-trivial trade-off.
