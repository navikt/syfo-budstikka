# Technical terminology, anglicisms, and text types

Read from the `klarsprak` skill as needed. It keeps the large tables out of
SKILL.md.

## Technical terms

### Always English (do not translate)

- **Infra/plattform:** image, cluster, node, container, deployment, release, namespace, pod, secret, NAIS, rollback, failover, backup, health check, readiness, liveness.
- **Git/PR-flyt:** pull request, merge, commit, branch, rebase, hotfix, bugfix, patch.
- **Backend/Ktor:** endpoint, route, payload, request, response, suspend, coroutine, Flow, scope, audience, claim, token, JWT, connection pool, offset, topic, consumer, producer, migration, schema.
- **Generelt:** edge case, bug, middleware, pipeline, runtime, framework.

### Norwegian is acceptable for

feilsøking, oppgradering, sikkerhetskrav, vedlikehold, tilgjengelighet, kodegjennomgang, avhengighet, kø, melding, kvittering, validering, oppslag.

### Compound words with an English term

Use a hyphen, not a separated compound: `Kafka-topicet`, `deploy-steg`,
`token-validering`, `Flyway-migrasjon`, `GitHub-repoet`, `health-endepunktet`,
`Postgres-operatoren`, `Kafka-consumeren`. «Postgres operatoren» and «Kafka
consumer» are incorrect as Norwegian phrases.

### Code versus prose

Code identifiers (`.kt`) have a stricter rule than prose: Norwegian only for
domain terms; everything else is English. `.github/instructions/kotlin.instructions.md`
is authoritative. `oppslag` is acceptable in Norwegian prose, but code uses
`lookup`. In this codebase, `Dispatch`, `Decision`, `Delivery`, `Recipient`,
`Foundation`, `Channel`, and `Reference` are English technical code terms, while
`Brukervarsel`, `Ledervarsel`, `DittSykefravaer`, and `Brev` are Norwegian domain terms.

## Anglicisms: use Norwegian

| Anglicism | Norwegian alternative |
|----------|-----------------|
| «adressere et problem» | «løse», «fikse», «ta tak i» |
| «på slutten av dagen» | «til syvende og sist», eller dropp |
| «ta eierskap til» | «ha ansvar for» |
| «delivere» | «levere» |
| «har du noen input?» | «har du innspill?» |
| «deploye» | «rulle ut» |
| «shippe» | «levere», «sende ut» |
| «reviewe» | «gå gjennom», «se over» |
| «release» (som verb) | «gi ut», «rulle ut» |
| «tracke» | «følge med på», «spore» |
| «aligne» | «samkjøre», «enes om» |
| «triage» | «prioritere», «sortere» |
| «være på samme side» | «være enige» |
| «i henhold til» (overbrukt) | «etter», «ifølge» |
| «per dags dato» | «nå», «i dag» |

## Text types: tone

| Text type | Tone | Guidance |
|-----------|------|------|
| ADR (`docs/adr/`) | English, neutral, technical | Context → Decision → Consequences. Present tense, active voice. |
| README | Norwegian, direct, friendly | Start with service purpose, then setup (`./gradlew run`, NAIS). Do not sell the project. |
| Log message | English, unambiguous, searchable | Concrete event plus key values. No inflated wording. Never personal data. |
| Error message / API response | Simple, actionable | What failed and what the recipient can do. Active voice. |
| Issue / PR description | English, concrete | What changes and why. Link the issue or ADR. |
| Commit message | English, concrete, imperative | Follow Conventional Commits. |

## Sources

- [Språkrådets klarspråk-prinsipper](https://sprakradet.no/Klarsprak/) og [KI-rapport](https://sprakradet.no/aktuelt/ki-sprakets-fallgruver/) (jan 2025)
- [ISO 24495-1](https://sprakradet.no/klarsprak/kunnskap-om-klarsprak/iso-standard-for-klarsprak/) — internasjonal klarspråk-standard
- [Digdirs klarspråk-veileder](https://www.digdir.no/klart-sprak/ny-veileder-om-klart-sprak-i-utvikling-av-digitale-tjenester/3603)
- [Termportalen](https://www.termportalen.no/) — norske faguttrykk (UiB/Språkrådet)
