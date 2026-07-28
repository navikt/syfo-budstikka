---
description: "Applies when editing the Norwegian-language README; keeps its prose concise, direct, and consistent with Nav language practice."
applyTo: "README.md"
---

# Norwegian README quality

`README.md` is written in Norwegian Bokmål. Keep technical prose as precise as
the code.

## Lead with the outcome

Start with the conclusion or decision, then give background and rationale.
Readers should understand the result early.

Prefer active verbs over nominalizations:

```text
❌ Vi foretar en vurdering av implementasjonen.
✅ Vi vurderer implementasjonen.

❌ Det er gjort en oppdatering av Flyway-migreringen.
✅ Vi oppdaterte Flyway-migreringen.
```

## Avoid generated-text mannerisms

- repeated em dashes in prose and bullet lists;
- formulas such as "ikke bare X, men også Y";
- clichés such as "i et stadig skiftende landskap";
- inflated adjectives such as "banebrytende", "revolusjonerende", "sømløs",
  and "robust"; and
- summary appendages such as "kort sagt" when they add nothing.

State what happens, who acts, and what the reader should do.

## Norwegian terminology

Use a natural Norwegian term when one exists, while preserving established
stack terms such as Ktor, Netty, Nais, TokenX, Azure AD, Flyway, Kafka,
consumer, producer, and endepunkt. Use a hyphen in Norwegian compounds with an
English technical term, for example `CI-pipeline`, `API-kall`,
`Kafka-consumer`, and `Flyway-migrering`.

Write the organization as **Nav**, not "NAV", in prose. Preserve technical
identifiers and proper names such as `NAIS`, `NAVident`, `no.nav.syfo`,
`no.nav.budstikka`, and URLs.
