# Glossary format

Keep `docs/glossary.md` as canonical domain language, not a specification or
technical decision log.

```markdown
## <optional group>

- **Term** — precise domain definition. _Avoid:_ synonym, overloaded wording.
```

Each entry defines one domain concept in business language. Keep table names,
Kafka topic names, classes, and endpoints in code or ADRs. Group naturally under
headings such as Actors, Events, and States. For several bounded contexts, add a
short map that names each owner and relationships; ask before assigning an
ambiguous term.
