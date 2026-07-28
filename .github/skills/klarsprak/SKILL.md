---
name: klarsprak
description: "Edit Norwegian README and user-facing product or API copy for clarity. Use when writing or language-editing Norwegian text while preserving English technical artifacts."
---
# Plain Norwegian

Use this skill when tightening Norwegian text in `README.md` or user-facing
product and API copy. Technical documentation, ADRs, issues, pull requests,
commit messages, logs, and operator interfaces remain English. Write **Nav** in
running Norwegian text, except for `NAIS`, `NAVident`, the Gradle group
`no.nav.syfo`, and package names under `no.nav.budstikka`.

The baseline rules live in `.github/instructions/norwegian-text.instructions.md`.
This skill is a short operational checklist.

## Rules (short version)

1. Start with the outcome in the first sentence.
2. Keep Norwegian README and product copy as short as possible. Remove details
   that do not affect a decision or action.
3. Write short, clear, concise sentences. Keep one point per sentence.
4. Use active voice.
5. Avoid duplication. State something once and remove repetition across paragraphs.
6. Keep technical terms in English. Do not translate established terms such as
   `happy path`, `use case`, `dependency injection`, `override`, `token`,
   `consumer`, or `endpoint`.
7. In code, use Norwegian only for canonical domain terms or quoted
   user-facing copy.
8. Use a hyphen in compound words that contain an English technical term.
9. Remove AI markers: inflated adjectives, excessive em dashes, and “not only X,
   but Y” constructions.
10. Never include PII, tokens, or credentials in examples or error messages.

## Short before-and-after examples

```text
❌ Per-melding atomisk er en hard invariant.
✅ Hver melding behandles atomisk.
```

```text
❌ Vi foretar en vurdering av endringen.
✅ Vi vurderer endringen.
```

## Boundaries

- Ask before restructuring an entire README.
- Do not change technical decisions while language-editing.

## References (as needed)

- `references/terminology-and-anglicisms.md`: what should stay Norwegian versus English.
- `references/before-and-after.md`: concrete rewriting examples.
- `references/ai-markers.md`: typical AI markers in Norwegian text.
