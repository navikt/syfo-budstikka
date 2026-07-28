# README content catalog

Read this when selecting sections, writing the purpose, or assessing database,
observability, and anti-patterns. The main skill owns repository reading and
quality boundaries.

## Relevant sections

| Section | When | What to read from the repository |
|---|---|---|
| Title + badges | Always | Repository name, workflow names, actual stack (Kotlin, Ktor, Gradle), and linters/formatters/test tools (ktlint/spotless, JUnit/Kotest). |
| Repository purpose | Always | See purpose below. |
| Mermaid diagram | Integrations, auth, or service flow | Actual flows: calling service → app (TokenX/Azure), Kafka in/out, PostgreSQL, outgoing API calls. See `section-templates.md`. |
| API overview | Repository exposes an API | Method, path, short description, and auth requirement for each endpoint. See `section-templates.md`. |
| Kafka | Consumer or producer | Topics, direction (in/out), and what is read, persisted, or republished. |
| Database | PostgreSQL/Flyway | What the database owns, central tables, and that Flyway manages the schema. |
| Authentication | `install(Authentication)` or `tokenx`/`azure` | Mechanism, protected endpoints, and valid callers (`accessPolicy.inbound`). |
| Development | Always | Short section near the end, stable local URL, and `./gradlew tasks` for current commands. |
| Read more | Docs exist | Links to `docs/`, ADRs, and architecture. |
| For Nav employees | Always | Team Slack as the final section and internal team information; for team-esyfo, [#esyfo on Slack](https://nav-it.slack.com/archives/C012X796B4L) is the default unless another channel is known. |

## Purpose, database, and observability

Explain the domain problem the backend solves, for which consumers (frontends,
other backends, or jobs), and any meaningful distinction in their API usage.

If the repository owns PostgreSQL, describe stored data and central tables
briefly; state that Flyway migrations under
`src/main/resources/database.migration/` manage the schema. Do not duplicate the
whole schema; point to the migrations.

If Grafana dashboards exist, link only verified URLs from `nais/` or the existing
README. Nav uses `https://grafana.nav.cloud.nais.io/` with team-specific dashboards;
never construct a URL.

## Anti-patterns

- Scaffold remnants: ktor.io links, an empty “Features” table, and generic
  build/run tables from Ktor Project Generator that were never replaced.
- Template cargo culting: a copied template with no adaptation to the repository.
- Zombie sections: stale sections that are never removed.
- Badge wall: more than five badges in a row without clear signal value.
- README bloat: more than 500 lines; split content into `docs/` instead.
- Command cargo culting: copied `./gradlew` commands instead of linking to
  `./gradlew tasks`.
- Stale examples: endpoints, topics, or paths that no longer work.
- Aspirational docs: describing what should exist rather than what exists.
- Happy-path only: omitting error handling or troubleshooting where it is needed.
