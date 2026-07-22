# Fagtermer, anglisismer og teksttyper — full referanse

Slås opp fra `klarsprak`-skillen ved behov. Holder de tunge tabellene ute av SKILL.md.

## Fagtermer

### Alltid engelsk (ikke oversett)

- **Infra/plattform:** image, cluster, node, container, deployment, release, namespace, pod, secret, NAIS, rollback, failover, backup, health check, readiness, liveness.
- **Git/PR-flyt:** pull request, merge, commit, branch, rebase, hotfix, bugfix, patch.
- **Backend/Ktor:** endpoint, route, payload, request, response, suspend, coroutine, Flow, scope, audience, claim, token, JWT, connection pool, offset, topic, consumer, producer, migration, schema.
- **Generelt:** edge case, bug, middleware, pipeline, runtime, framework.

### Norsk er OK for

feilsøking, oppgradering, sikkerhetskrav, vedlikehold, tilgjengelighet, kodegjennomgang, avhengighet, kø, melding, kvittering, validering, oppslag.

### Sammensatte ord med engelsk term

Bruk bindestrek, ikke særskriving: `Kafka-topicet`, `deploy-steg`, `token-validering`, `Flyway-migrasjon`, `GitHub-repoet`, `health-endepunktet`, `Postgres-operatoren`, `Kafka-consumeren`. «Postgres operatoren» og «Kafka consumer» (som norsk frase) er feil.

### Kode vs. prosa

Kode-identifikatorer (`.kt`) følger en strengere regel enn prosa: norsk KUN på domeneord, alt annet engelsk — `.github/instructions/kotlin.instructions.md` er fasit. `oppslag` er OK i norsk prosa, men i kode skal det være `lookup`. I denne kodebasen er `Dispatch`, `Decision`, `Delivery`, `Recipient`, `Foundation`, `Channel` og `Reference` tekniske kodeord (engelsk), mens `Brukervarsel`, `Ledervarsel`, `DittSykefravaer` og `Brev` er domeneord (norsk).

## Anglisismer — bruk norsk

| Anglisisme | Norsk alternativ |
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

## Teksttyper — tone

| Teksttype | Tone | Tips |
|-----------|------|------|
| ADR (`docs/adr/`) | Nøytral, teknisk | Kontekst → Beslutning → Konsekvenser. Beslutning i presens, aktiv form. |
| README | Direkte, vennlig | Start med hva tjenesten gjør, deretter oppsett (`./gradlew run`, NAIS). Ikke selg prosjektet. |
| Loggmelding | Entydig, søkbar | Konkret hendelse + nøkkelverdier. Ingen svulstige ord. Aldri personopplysninger. |
| Feilmelding / API-respons | Enkel, handlingsrettet | Hva gikk galt + hva mottakeren kan gjøre. Aktiv form. |
| PR-beskrivelse | Konkret | Hva endres, hvorfor. Lenk til issue/ADR. |
| Commit-melding | Konkret, imperativ | Følg conventional commits. |

## Kilder

- [Språkrådets klarspråk-prinsipper](https://sprakradet.no/Klarsprak/) og [KI-rapport](https://sprakradet.no/aktuelt/ki-sprakets-fallgruver/) (jan 2025)
- [ISO 24495-1](https://sprakradet.no/klarsprak/kunnskap-om-klarsprak/iso-standard-for-klarsprak/) — internasjonal klarspråk-standard
- [Digdirs klarspråk-veileder](https://www.digdir.no/klart-sprak/ny-veileder-om-klart-sprak-i-utvikling-av-digitale-tjenester/3603)
- [Termportalen](https://www.termportalen.no/) — norske faguttrykk (UiB/Språkrådet)
