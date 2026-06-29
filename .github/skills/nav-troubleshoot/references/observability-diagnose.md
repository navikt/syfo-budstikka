# Observability-diagnose — når metrics, logs og traces ikke peker samme vei

Bruk denne referansen når dette Ktor-backendet (`no.nav.syfo`) kjører, men symptombildet er uklart: høy feilrate uten tydelig stacktrace, latency uten klar flaskehals, restarts uten sikker rotårsak, eller manglende sammenheng mellom Mimir, Loki og Tempo.

Dette er en NAV-spesifikk diagnosehjelp for **Mimir + Loki + Tempo på Nais**. Den lærer ikke Prometheus eller Grafana fra bunnen. Den hjelper deg å velge riktig startpunkt og unngå vanlige feiltolkninger.

## Første sjekk

- Avklar **symptom**: feilrate, latency, restart, manglende traces eller uklare logger
- Avklar **scope**: app, namespace, cluster (`dev-gcp` vs `prod-gcp`) og tidsvindu
- Avklar **stack**: HTTP-only eller også Kafka, Micrometer-/auto-instrumentation aktiv eller ikke
- Sjekk om symptomene startet ved siste deploy, config-endring eller økt trafikk

## Diagnostisk tre

```text
Observability-signalene spriker
├── Høy feilrate?
│   ├── Start i Mimir: rate på 4xx/5xx per route
│   ├── Finn samme tidsvindu i Loki
│   └── Hvis loggene har trace_id → slå opp i Tempo
├── Høy latency?
│   ├── Start i Mimir: p95/p99 per route eller operation
│   ├── Tempo: finn langsomme spans
│   └── Verifiser om tregheten ligger i appen, DB eller downstream-kall
├── Restarts / OOM / throttling?
│   ├── Start i pod-diagnose.md + Mimir for memory/restarts
│   ├── Loki: se siste logger før restart
│   └── Ikke konkluder med "app-bug" før limits/requests er sjekket
└── Mangler traces eller korrelasjon?
    ├── Sjekk at appen faktisk sender spans i denne runtimeen
    ├── Sjekk at logs har trace_id/callId
    └── Sjekk at du ser på riktig service og riktig cluster
```

## Startpunkt per symptom

### 1. Feilrate uten tydelig årsak

Begynn i **Mimir** for å finne hvilken route eller operasjon som feiler. Gå deretter til **Loki** i samme tidsvindu. Ikke start med fritekstsøk i alle logger.

- Filtrer først på stabile labels: `app`, `namespace`, `cluster`, eventuelt route/status
- Se etter topp i 5xx før du tolker 4xx som serverfeil (4xx er ofte klient-/auth-side)
- Når du finner ett konkret logg-eksempel: følg `trace_id` eller `callId` videre

### 2. Latency uten feil

Begynn i **Mimir** for p95/p99 (`http_server_requests_seconds`) og gå deretter til **Tempo**.

- Se etter om tregheten er konsistent eller bare gjelder enkeltraces
- Sammenlign route/operation før og etter siste deploy
- Hvis én span dominerer: sjekk DB-kall, downstream-HTTP (Ktor-klient) eller Kafka-venting før du foreslår kodeendring

### 3. Restarts, memory pressure eller CPU-problemer

Kombiner **pod-diagnose.md** og observability:

- Mimir bekrefter trend: minne opp mot limit, restart-telling, eventuelt throttling
- Loki viser hva appen gjorde rett før restart
- `OOMKilled` er som regel ikke løst før minneprofil eller limits er vurdert
- CPU-throttling på Nais er ofte selvpåført hvis CPU-limits er satt — fjern dem

### 4. "Tempo viser rare traces"

Vanlig NAV-gotcha: Tempo-søk kan gi treff som ikke egentlig tilhører tjenesten du feilsøker.

- Verifiser `rootServiceName` eller tilsvarende tjenestenavn før du tolker trace-resultatet
- Sjekk riktig cluster (`dev-gcp` vs `prod-gcp`)
- Hvis appen ikke eksponerer spans ennå: gå tilbake til logs og metrics i stedet for å gjette

### 5. "Loki finner for mye eller for lite"

- Start med labels først, JSON-parsing etterpå
- Ikke søk bredt på ord som `error` uten `app` og tidsvindu
- Hvis logger mangler `trace_id` eller `callId`, noter det som observability-gap, ikke som bevis på at feilen ligger et annet sted

## NAV-gotchas

- **Samme tidsvindu først**: sammenlign ikke et trace fra nå med logger fra forrige deploy
- **Samme identifikator**: bruk `trace_id` eller `Nav-Call-Id` gjennom hele kjeden når de finnes
- **Riktig miljø**: verifiser `cluster`, `namespace` og app-navn før du trekker konklusjoner
- **Manglende signal er også et funn**: ingen traces kan bety manglende instrumentering, feil service-navn eller at feil miljø er valgt
- **Ikke hopp rett til dashboard-fiks**: diagnostiser runtime-feilen først; forbedre dashboards etterpå
- **PII**: logg `Nav-Call-Id`/`callId`, aldri fnr/tokens/navn — gjelder også midlertidige debug-logger

## Når du skal eskalere

- Til [pod-diagnose.md](./pod-diagnose.md) når problemet i praksis er restart, readiness eller ressursmangel
- Til [database-diagnose.md](./database-diagnose.md) når langsomme spans eller logger peker på pool/query-problem
- Til [auth-diagnose.md](./auth-diagnose.md) når feilraten primært er 401/403
- Til `/diagnosing-bugs` (perf-grenen) når rotårsaken krever en baseline-måling og bisect, eller når funnet er manglende instrumentering / feil labels / manglende `trace_id` i logger — løft det i så fall til en ADR i `docs/adr/`
