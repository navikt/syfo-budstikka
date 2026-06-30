---
name: nav-troubleshoot
description: "Bruk når Ktor-backendet feiler i DRIFT på NAIS: pod starter ikke / CrashLoopBackOff / OOMKilled, 401/403, Kafka consumer-lag, DB-/HikariCP-/Flyway-feil ved startup, eller sprik mellom Mimir/Loki/Tempo. For design av schema/manifest/auth, se /nais-manifest, /auth-overview, /flyway-migration."
---

# Nav Troubleshoot — plattform-diagnostikk

Strukturerte diagnostiske trær for kjøre-tids-symptomer på NAIS for dette repoet. Bruk denne skillen når noe **feiler i drift** (pod krasjer, 401/403, consumer lag, DB-timeout) — ikke når du skal designe eller endre schema / manifest / auth.

Skillen ruter symptom → riktig diagnostisk tre. Den fikse-disiplinerte delen (feedback-loop, repro, hypoteser, regresjonstest) bor i `/diagnosing-bugs` — start her for å lokalisere årsaken, gå dit for å lukke feilen. Generisk Kubernetes-/Kafka-/SQL-kunnskap er ikke replikert; bruk den fra eget repertoar.

## Arbeidsflyt

1. **Identifiser symptomet** før du kjører kommandoer — hva feiler konkret, i hvilket `cluster`/`namespace`, fra når?
2. **Detekter faktisk stack i denne kodestien** — plain Apache Kafka clients vs. Rapids & Rivers, Azure AD vs. TokenX vs. ID-porten/Maskinporten. Diagnosen må matche det appen faktisk kjører, ikke hva manifestet kunne hatt.
3. **Følg diagnostisk tre** i riktig `references/*.md` — steg for steg.
4. **Foreslå minst invasive fiks først**; eskaler kun hvis nødvendig.
5. **Lukk feilen via `/diagnosing-bugs`** — skriv regresjonstest (`./gradlew test`, Ktor `testApplication { }`) der det finnes en korrekt søm, og noter ferskt grønt bevis i `.grill/VERIFICATION.md`.

## Symptom-oversikt

| Symptom | Start her |
|---------|-----------|
| Pod starter ikke / CrashLoopBackOff / OOMKilled / ImagePullBackOff / Pending | [references/pod-diagnose.md](./references/pod-diagnose.md) |
| 401 Unauthorized / 403 Forbidden (TokenX / Azure AD / Texas) | [references/auth-diagnose.md](./references/auth-diagnose.md) |
| Kafka consumer lag / meldinger prosesseres ikke | [references/kafka-diagnose.md](./references/kafka-diagnose.md) |
| DB-tilkoblingsfeil / HikariCP pool exhaustion / Flyway-feil | [references/database-diagnose.md](./references/database-diagnose.md) |
| Feilrate, latency eller restarts der signalene spriker mellom metrics, logs og traces | [references/observability-diagnose.md](./references/observability-diagnose.md) |
| Treg responstid | Se kort tre under |
| Deploy feiler | Se kort tre under |

## Ytelsesproblemer (kort)

```
Treg responstid
├── Hvor er flaskehalsen?
│   └── Mimir (http_server_requests_seconds), Tempo (trace), Loki (logg) — samme tidsvindu
├── Database-queries? → EXPLAIN ANALYZE, N+1, paginering (se /postgresql-review)
├── Ekstern tjeneste treg? → timeout/retry i Ktor-klienten, circuit breaker, caching
└── Ressursbegrensning?
    ├── CPU throttling → ALDRI sett CPU limits på NAIS (bruk kun requests)
    └── Memory pressure → øk `resources.limits.memory` (se /nais-manifest)
```

Se [references/observability-diagnose.md](./references/observability-diagnose.md) for NAV-spesifikk diagnostikk i Mimir/Loki/Tempo. Mål før du fikser — etabler baseline (Micrometer-timer, `measureTimedValue {}`, `EXPLAIN ANALYZE`), så bisect.

## Deploy-problemer (kort)

```
Deploy feiler
├── GitHub Actions-feil? → Build/Docker/Push — sjekk actions-log og GAR-tilgang
├── Nais deploy-feil?
│   ├── "invalid manifest" → valider YAML (se /nais-manifest)
│   ├── "unauthorized" → sjekk deploy-key / workload identity
│   └── "resource quota exceeded" → team-kvote
└── Deploy OK men app feiler? → bruk references/pod-diagnose.md
```

## Relaterte skills

- `/nais-manifest` — manifest-struktur, resources, probes, accessPolicy, GCP Postgres, Kafka pool
- `/auth-overview` — Azure AD, TokenX, ID-porten, Maskinporten, Texas-sidecar (mekanismene bak auth-diagnose)
- `/kafka-topic` — consumer/producer-mønstre, SSL-env, idempotens, Rapids & Rivers
- `/flyway-migration` og `/postgresql-review` — schema, migrering, query- og indeksvurdering (design-tid)
- `/observability-setup` — Micrometer/Prometheus + Mimir/Loki/Tempo-oppsett (design-tid; nav-troubleshoot leser signalene, observability-setup etablerer dem)
- `/diagnosing-bugs` — feedback-loop, repro, hypoteser, regresjonstest; sporer arbeid i `.grill/` på linje med @grillmester sin faseløkke

## Grenser

### Alltid

- Start med symptomet; ikke spekuler på årsak før logs/events er sjekket.
- Følg det diagnostiske treet steg for steg; verifiser `cluster`/`namespace`/app-navn før du konkluderer.
- Sjekk `kubectl logs --previous` ved CrashLoopBackOff.
- Respekter appens faktiske stack — ikke foreslå Rapids & Rivers-fiks på en plain Kafka-konsument eller omvendt.

### Spør først

- Endre produksjons-konfigurasjon (resources, replicas, secrets, accessPolicy).
- Restart av pods i produksjon.
- Endring av database-konfigurasjon eller `maximumPoolSize`.

### Aldri

- Endre secrets direkte i klusteret (gå via kildekontroll / NAIS).
- Kjør `kubectl delete pod` i prod uten å forstå årsaken.
- Ignorer `OOMKilled` — den kommer tilbake.
- Sett CPU-limits på NAIS — forårsaker throttling.
- Logg fnr, tokens, navn eller særlige kategorier under feilsøking — logg `Nav-Call-Id`/`callId`, ikke personopplysninger.
