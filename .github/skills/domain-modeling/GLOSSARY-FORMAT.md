# `docs/glossary.md` Format

This repository maps upstream `CONTEXT.md` glossary semantics to
`docs/glossary.md`.

## Structure

```md
# Glossar — {context name}

{One or two sentences describing what this context is and why it exists.}

## {Natural term group}

**Term**:
{A one or two sentence description of the term}
_Unngå_: Alternative term, overloaded term

**Another term**:
{A one or two sentence description of the term}
_Unngå_: Alternative
```

Preserve the glossary's established language, headings, and `_Unngå_` marker
when adding an entry.

## Rules

- **Be opinionated.** When multiple words exist for the same concept, pick the
  best one and list the others under `_Unngå_`.
- **Keep definitions tight.** One or two sentences max. Define what it IS, not
  what it does.
- **Only include terms specific to this project's context.** General
  programming concepts don't belong even if the project uses them
  extensively.
- **Exclude implementation details.** Table names, Kafka topics, class names,
  endpoints, and authentication mechanics belong in code, ADRs, or maintained
  topic documents.
- **Group terms under subheadings** when natural clusters emerge. If all terms
  belong to one cohesive area, a flat list is fine.

This repository currently has one context. Do not create a context map during
a modeling session. If the architecture genuinely becomes multi-context,
update `docs/agents/domain.md` and establish the new documentation ownership
as a dedicated architectural change.
