---
name: readme-update
description: "Create or update README.md from the repository's actual API, Kafka, database, NAIS, and authentication setup. Use when README content is missing or stale."
---

# README update

Use this skill to create or update a README. Make the README describe what the
`no.nav.budstikka` backend does today — not a generic template or an intended future.

Focus on what a backend reader needs: service purpose, exposed APIs, Kafka
consumption or production, owned database, caller authentication, and how other
services reach it. Omit frontend-specific conventions (deployable-UI environment
links, microfrontend tables, Nav decorator, and Storybook).

## Language boundary

Write user-facing README and product copy in Norwegian Bokmål. Keep technical
identifiers, canonical Norwegian domain terms in code, URLs, commands, and
established Nav names unchanged. This skill's agent guidance, like other
technical documentation, remains English.

## Step 1: Read the repository first

Read actual sources before writing any README text:

1. **Existing README** — retain manual content, Slack channels, wiki links, and
   durable operational advice that remain correct. Generated Ktor scaffolding
   (ktor.io links, empty “Features” table, `./gradlew run` table) is not manual
   content and should be replaced.
2. **Stack and build** — `build.gradle.kts`, `settings.gradle.kts`,
   `gradle/libs.versions.toml`, and `gradle.properties`. Read the `group`
   (`no.nav.syfo`), `mainClass` (usually `io.ktor.server.netty.EngineMain`), JVM
   toolchain, Ktor version catalog entry, and modules actually on the classpath.
3. **Nais manifests** — `nais/nais-*.yaml` for environments, ingresses,
   `gcp.sqlInstances`, Kafka, `accessPolicy`, and `azure`/`tokenx`. The manifest
   is authoritative for platform configuration, but auth must also exist in code.
4. **Code** — `src/main/kotlin/no/nav/budstikka/` for `Application.module()`,
   routes, Ktor plugins, Kafka, clients, and data access. Read
   `src/main/resources/application.conf` and Flyway files under
   `src/main/resources/database.migration/`.
5. **CI/CD** — `.github/workflows/` for workflow names used in a CI badge and
   the deployment flow.
6. **Docs** — `docs/`, ADRs, and architecture notes. Link to them instead of
   duplicating long explanations.

Clarify at least:

- What is the backend's primary responsibility and domain?
- Which environments actually exist under `nais/`?
- Which REST/GraphQL endpoints are exposed, and which require auth?
- Does it consume or produce Kafka, on which topics, and in which direction?
- Does it own a PostgreSQL database with Flyway migrations?
- Which clients does it call (via TokenX or Azure AD), and which services call
  it through `accessPolicy.outbound`?

## Step 2: Choose relevant sections

Choose only sections the repository needs, ordered purpose → integrations →
development → metadata. Read [references/content-catalog.md](references/content-catalog.md)
for section selection, anti-patterns, purpose, database, and observability;
read [references/section-templates.md](references/section-templates.md) only when writing
badges, Mermaid, or an API table.

## Step 3: Create or update

### Update an existing README

- Keep sections that remain correct.
- Update only stale content; do not rewrite everything without a reason.
- Preserve correct manual details such as Slack channels, wiki links, and
  operational advice.
- Keep useful existing sections that this skill does not name.
- Replace generated Ktor scaffolding with content that describes the real domain.

### Choose the title

- Propose three README titles and ask the user before fixing the title.
- Option 1: the current repository name (`syfo-budstikka`).
- Option 2: a domain-oriented title based on what the backend actually does.
- Option 3: a different domain-oriented angle, more technical or focused on
  what the service delivers to consumers.
- Option 4: a title supplied by the user.
- Explain briefly: names in the `syfo` family are often cryptic, while a
  domain-oriented title helps a new reader understand the README in seconds.

### Create a new README

- Always include title, badges, purpose, a diagram (or a short integration list
  when clearer), and development.
- Add only sections the backend actually needs.
- Use the repository's actual endpoint, topic, database, and environment names.

### Quality rules

- Follow the cognitive funnel: title → purpose/context → API/Kafka/database/auth
  → development → metadata. Readers scan top to bottom.
- Do not invent endpoints, topics, databases, auth, or environments. Check code
  and `nais/`.
- Do not claim auth configuration before finding `install(Authentication)` in
  code or `tokenx`/`azure` in the manifest.
- When information for an always-needed section is missing, retain existing text
  or ask the user.
- In development, point to `./gradlew tasks` instead of copying concrete Gradle
  commands, so readers always see the current task list.
- Use short, plain language for purpose, development, and contact. A README is
  an entry point, not a complete internal wiki.

## Section templates — badges, Mermaid, API table

Read [references/section-templates.md](references/section-templates.md) for templates and
rules for the three heaviest sections: CI and technology badges, a Mermaid diagram,
and the API table.

## Boundaries

### Always

- Read real repository content (`build.gradle.kts`, `nais/`,
  `src/main/kotlin/no/nav/budstikka/`, `application.conf`, and workflows) before
  writing the README.
- Cross-check README text against code, manifests, and workflows.
- Preserve correct manual content.
- Describe auth and integrations in Nav context when present: TokenX, Azure AD,
  `accessPolicy`, Kafka topics, and PostgreSQL.

### Ask first

- If you would have to guess environment links, Slack channel, or team name.
- If important product context cannot be inferred from the repository.
- Before removing large manual sections that may be intentional.

### Never

- Write a generic README without reading the repository.
- Invent APIs, topics, dashboards, auth, or environments.
- Overwrite manual content uncritically.
- Document an intended future as already implemented.
- Teach general Markdown or Mermaid syntax in this README skill.
