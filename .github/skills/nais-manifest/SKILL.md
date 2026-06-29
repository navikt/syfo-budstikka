---
name: nais-manifest
description: "Bruk når dette Ktor-backendet (no.nav.syfo) skal få nytt eller endret NAIS-manifest — nais.yaml for Application eller Naisjob: ingress, resources, probes, accessPolicy, Azure AD/TokenX, Kafka eller GCP Postgres. Trigger: 'lag et nais-manifest', 'eksponer appen', 'legg til database/Kafka/auth i nais', 'skaler opp i prod', CPU-throttling, OOM-kill, batch-/cron-jobb. Kalles via /nais-manifest."
---

# NAIS-manifest — Ktor-backend (no.nav.syfo)

Lag eller oppdater et komplett NAIS-manifest (`Application` for den kontinuerlige tjenesten, `Naisjob` for batch). Dette er et Kotlin/Ktor-backend på JVM (Netty) — fokuser på server-port, JVM-runtime og JVM-observability, ikke frontend.

## Fremgangsmåte

1. **Les eksisterende manifester først** under `.nais/`, `nais/` eller tilsvarende `*.yaml`. Gjenbruk reell `namespace`, `labels.team`, health-paths og prometheus-path — ikke anta verdier.
2. Hvis repoet ikke har manifest enda: bruk team-namespace fra `.github/copilot-instructions.md` / README, og følg malene under.
3. Kartlegg hva appen faktisk trenger: database (Postgres), Kafka, auth (TokenX/Azure AD), ingress, scaling — eller om det er en batch-jobb (`Naisjob` i stedet for `Application`).
4. Bruk miljøspesifikke manifester (`*-dev.yaml`, `*-prod.yaml`) når repoet allerede følger det mønsteret.

## Application-mal

```yaml
apiVersion: nais.io/v1alpha1
kind: Application
metadata:
  name: syfo-budstikka
  namespace: team-esyfo          # Les fra eksisterende manifest
  labels:
    team: team-esyfo
spec:
  image: {{ image }}
  port: 8080                     # Ktor/Netty lytter på denne

  prometheus:
    enabled: true
    path: /internal/prometheus   # Sjekk faktisk path i Ktor-routingen
  liveness:
    path: /internal/isalive      # Sjekk eksisterende — kan også være /isAlive
    initialDelay: 5
  readiness:
    path: /internal/isready
    initialDelay: 5

  resources:
    requests:
      cpu: 50m
      memory: 256Mi
    limits:
      memory: 512Mi              # Sett aldri cpu-limits — se regel under
```

**Viktig:** Verifiser `port`, `prometheus.path`, `liveness.path` og `readiness.path` mot Ktor-routingen (`embeddedServer`/`routing { ... }`) før du commiter. Endepunktene må eksistere i koden.

## Regel: ingen CPU-limits i NAIS

Sett **aldri** `resources.limits.cpu` — bare `requests.cpu`.

**Hvorfor:** Kubernetes CFS-quota håndhever CPU-limits i 100ms-vinduer. Når en container treffer grensen kort, blir hele containeren throttlet resten av vinduet — også tråder som ikke trenger CPU. På JVM rammer dette ekstra hardt under oppstart (JIT-kompilering, klasselasting) og GC, og gir latenshaler og timeouts. NAIS anbefaler `requests.cpu` for scheduling og lar noden håndtere faktisk forbruk.

Memory-limits derimot **skal settes** — uten limit kan en container ta ned hele noden via OOM.

## Ressursstartpunkter (JVM)

| Størrelse | `requests.cpu` | `requests.memory` | `limits.memory` |
|-----------|----------------|-------------------|-----------------|
| Liten     | 50m            | 256Mi             | 512Mi           |
| Middels   | 100m           | 512Mi             | 1Gi             |
| Stor      | 200m           | 1Gi               | 2Gi             |

JVM trenger headroom over heap til metaspace, tråder og direct buffers — sett `limits.memory` over `-Xmx`. Juster basert på faktisk forbruk i Grafana. `replicas: { min: 2, max: 4, cpuThresholdPercentage: 80 }` er greit startpunkt for prod.

## accessPolicy — alltid eksplisitt

Definer både `inbound` og `outbound`. Glem ikke `namespace` (og `cluster` ved kall på tvers av klynger):

```yaml
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-kallende
      - application: annen-tjeneste
        namespace: annet-team
        cluster: prod-gcp
  outbound:
    rules:
      - application: pdl-api
        namespace: pdl
      - application: syfo-nedstroms
        namespace: team-esyfo
    external:
      - host: api.ekstern-tjeneste.no
```

`accessPolicy` må holdes i sync med auth-koden: innkommende tokens som ikke matcher `inbound.rules` avvises på plattformnivå. Drift mellom manifest og kode er en feil. Auth-mekanismer og audience-format: se `/auth-overview`.

## PostgreSQL (GCP SQL)

```yaml
gcp:
  sqlInstances:
    - type: POSTGRES_17          # Sjekk repoets versjon
      tier: db-f1-micro          # dev; prod: db-custom-1-3840
      highAvailability: false    # prod: true
      diskAutoresize: false      # prod: true
      databases:
        - name: budstikka-db
          envVarPrefix: DB
```

Gir env: `DB_HOST`, `DB_PORT`, `DB_DATABASE`, `DB_USERNAME`, `DB_PASSWORD`. Schema-endringer og migreringer kjøres med Flyway — se `/flyway-migration`. For langvarige migreringer: sørg for `startup`-probe slik at Flyway fullfører før liveness restarter poden.

### HikariCP i containere — pool 3–5, ikke default 10

HikariCP default `maximumPoolSize` er 10. I NAIS-containere er det feil:

- Containeren har en brøkdel av CPU-kjerner (`requests.cpu: 100m` ≈ 0,1 kjerne).
- 10 aktive forbindelser gir tråd-kontensjon og kontekstsvitsj-overhead.
- Cloud SQL Proxy og Postgres har egne grenser; mange poolere × mange replicaer sprenger serveren.

**Start med `maximumPoolSize: 3–5`** i `HikariConfig`. Øk bare hvis metrikker viser pool-exhaustion (`hikaricp_connections_pending`). Formelen `connections = ((cores * 2) + effective_spindle_count)` ([HikariCP Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)) gir lave tall i containere.

## Kafka

```yaml
kafka:
  pool: nav-dev                  # Eller nav-prod
```

Topic-navnekonvensjon: `{team}.{domene}.v{versjon}`. Dev: `partitions: 1, replication: 1`. Prod: `partitions: 6+, replication: 3`. En kontinuerlig kø-konsument hører hjemme i `Application`, ikke `Naisjob`.

## Azure AD og TokenX

```yaml
azure:
  application:
    enabled: true
    tenant: nav.no               # Eller trygdeetaten.no

tokenx:
  enabled: true                  # On-behalf-of for innlogget bruker
```

- Bruker-/personkontekst inn og videre nedstrøms → **TokenX (OBO)**.
- Ren maskin-til-maskin uten personkontekst → **Azure AD client_credentials**, eller **Maskinporten** for eksterne organisasjoner.
- Partner som handler på vegne av virksomhet i Altinn → **Altinn 3 systembruker** (Maskinporten + systembruker-token).

Valg av mekanisme, token-validering i Ktor og audience-format er dekket av `/auth-overview` — ikke dupliser auth-detaljene her.

## Ingress — velg riktig domene

| Domene             | Bruk                                                      |
|--------------------|-----------------------------------------------------------|
| `*.nav.no`         | Publikumsrettede brukerflater                             |
| `*.intern.nav.no`  | Interne ansattflater (krever NAV-nettverk/naisdevice)     |
| `*.ekstern.nav.no` | Eksterne brukerflater som ikke ligger på nav.no           |

Dev-varianter: `*.dev.nav.no`, `*.intern.dev.nav.no`, `*.ekstern.dev.nav.no`. Et rent API som bare kalles av andre NAV-apper trenger ofte **ingen** ingress — bruk `accessPolicy.inbound` i stedet.

```yaml
ingresses:
  - https://syfo-budstikka.intern.dev.nav.no
```

## Observability (auto-instrumentation)

```yaml
observability:
  autoInstrumentation:
    enabled: true
    runtime: java
```

Tracing → Tempo, logger → Loki (logg til stdout/stderr, gjerne JSON via Logback), metrikker → Prometheus. Eksponer Micrometer/Prometheus-registry på `prometheus.path`.

## Pod-lifecycle og graceful shutdown

NAIS injiserer `preStop` med `sleep 5` før `SIGTERM`, og lastbalansereren slutter å rute trafikk før signalet sendes. Readiness-probes er **ikke** del av shutdown — manuell readiness-toggling i app-kode er et anti-mønster. Bruk Ktors ordinære shutdown (`ApplicationStopping`/`ApplicationStopped`) til å drenere og lukke connection pool/Kafka-consumer rent. Detaljer og anti-mønstre: se [`references/pod-lifecycle.md`](references/pod-lifecycle.md).

## Naisjob — batch-jobber

Bruk `Naisjob` når teamet trenger batch-kjøringer (nattlige jobber, engangs-migreringer, rapporter): kjører til fullført i stedet for kontinuerlig, ingen innkommende HTTP. Samme blokker for `resources`, `accessPolicy`, `gcp`, `kafka` og `azure` som `Application`, pluss `schedule` (cron), `activeDeadlineSeconds` og `backoffLimit`. Full mal med Kafka og Azure AD: [`references/naisjob-example.md`](references/naisjob-example.md).

## Kobling til faseløkken

- Fang varige plattformvalg som ADR i `.grill/adr/` — f.eks. valg av ingress-domene, scaling-strategi, Postgres-tier/HA, Kafka-pool og pool-størrelse. Noter manifest-relevante beslutninger i `.grill/CONTEXT.md`.
- Endring i `accessPolicy`, auth-flagg eller scopes → kjør `/security-review` (loggføres i `.grill/VERIFICATION.md`).
- Endring i prod-resources, replicas eller nye GCP-ressurser (kostnad) → kjør `grill-inspektor` før merge og legg evidens i `.grill/VERIFICATION.md`.

NAIS-dok: https://doc.nais.io/ · Golden Path: https://sikkerhet.nav.no/docs/goldenpath/

## Grenser

### Alltid
- Inkluder liveness, readiness og metrics-endepunkter som faktisk finnes i Ktor-routingen.
- Sett `resources.limits.memory` (hindrer OOM-kill av noden).
- Definer eksplisitt `accessPolicy` (inbound + outbound).
- Bruk miljøspesifikke manifester (`*-dev.yaml`, `*-prod.yaml`) når repoet gjør det.

### Spør først
- Endring i prod-resources, replicas eller `cpuThresholdPercentage`.
- Nye GCP-ressurser (kostnad).
- Endring i `accessPolicy` i prod.
- Nye ingress-domener.

### Aldri
- Sett `resources.limits.cpu` (CFS-throttling rammer JVM hardt).
- Fjern `resources.limits.memory`.
- Lagre hemmeligheter i Git.
- Hopp over health-endepunkter.
- Bruk default HikariCP `maximumPoolSize: 10` i en container.
- Senk `terminationGracePeriodSeconds` under default 30s.
