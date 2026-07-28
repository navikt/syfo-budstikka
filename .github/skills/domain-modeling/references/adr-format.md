# ADR format

Use one ADR for one hard-to-reverse decision with real alternatives. Keep it
short and decision-oriented.

```markdown
# ADR NNNN: <decision>

## Status
Accepted | Superseded by ADR NNNN | Deprecated

## Context
<facts, constraints, and the decision to make>

## Decision
<chosen option and its boundary>

## Consequences
<positive, negative, operational, migration, and verification effects>

## Alternatives considered
- <alternative> — <why rejected>
```

Choose ADRs for database/Flyway schema, Kafka contracts, authentication provider,
NAIS `accessPolicy`, deployment target, or similarly expensive reversals. Do not
use one for routine library choices, implementation details, temporary plans, or
facts that belong in the glossary.
