---
name: observability-setup
description: "Bruk ved etablering eller forbedring av observability i syfo-budstikka: Micrometer-metrikker og PrometheusMeterRegistry i Ktor, MicrometerMetrics-plugin, /internal/isalive|isready|prometheus, strukturert JSON-logging med trace_id/callId, korrelasjons-ID (Nav-Callid/x_request_id), OpenTelemetry-tracing, PromQL/LogQL, Grafana-dashboards og Prometheus-alerts i NAIS — eller når noen sier /observability-setup."
---

# Observability i syfo-budstikka

Ktor 3.x på Netty, pakke `no.nav.syfo`, kjører i NAIS. Hold hovedreglene her korte — bruk `references/` for fullstendige eksempler.

- **Metrikker** forteller *hva* som skjer
- **Logger** forklarer *hvorfor* det skjedde
- **Traces** viser *hvor* i flyten det skjedde
- Verifiser alltid eksisterende oppsett i repoet før du legger til nye målepunkter, labels eller varsler

## Arbeidsflyt

1. Les NAIS-manifestet, `src/main/resources/application.yaml`, `logback.xml` og `build.gradle.kts`/`gradle/libs.versions.toml` for eksisterende observability-oppsett.
2. Finn etablerte mønstre for `MicrometerMetrics`, `MeterRegistry`-injeksjon (Koin), `CallId`/`CallLogging`, MDC-felt og health-routes.
3. Verifiser hvilke endepunkter NAIS faktisk scraper og prober mot: `/internal/isalive`, `/internal/isready`, `/internal/prometheus` (eller `/internal/metrics`). Stiene i koden må matche manifestet.
4. Start med standardmetrikker (Ktor HTTP-server + JVM) og utvid med domenemetrikker som gir operativ verdi.
5. Legg til dashboards og varsler først når metrikkene og label-settet er stabile.

## Metrikker i Ktor (Micrometer)

Ktor har ingen Actuator: registry, `MicrometerMetrics`-plugin og de interne rutene
settes opp eksplisitt. Repoets faktiske oppsett i `infrastructure/metrics/` og
`bootstrap/` er fasit — les det framfor et eksempel her.

To valg som ikke er opplagte:

- `liveness` (`/internal/isalive`) skal bare svare på om prosessen bør restartes.
  `readiness` (`/internal/isready`) skal avhenge av faktiske avhengigheter, men
  holdes lett. Consumer-lag hører hjemme i varsling, aldri i liveness
  (se [helsesjekk](../../../docs/helsesjekk.md)).
- `distributionStatisticConfig` med `percentilesHistogram(true)` kreves for p95/p99
  fra Prometheus — uten den finnes ikke bucketene.

Se `references/micrometer.md` for `Counter`/`Timer`/`Gauge`/`DistributionSummary`, Koin-injeksjon, domene- og Kafka-metrikker.

## Navngivning for metrikker og labels

Prometheus-navnekonvensjonene (`snake_case`, `_total`, `_seconds`) er standard og
gjelder som ellers. Det NAIS-spesifikke er labelene:

### NAIS-label-konvensjoner

NAIS legger automatisk på et sett labels. Ikke dupliser disse på egne metrikker — bruk dem i queries, dashboards og varsler:

- `app` — applikasjonsnavn fra NAIS-manifestet (`syfo-budstikka`)
- `team` / `namespace` — eierskap og Kubernetes-namespace, brukes til alert-ruting
- `cluster` — `dev-gcp` / `prod-gcp`

Egne labels skal dekke domeneaspekter:
- Gode: `method`, `route`, `status`, `event_type`, `result`, `consumer_group`, `topic`
- Dårlige (høy kardinalitet / PII): `user_id`, `fnr`, `aktor_id`, `trace_id`, `callId`, rå URL-er med dynamiske segmenter
- Foretrekk normaliserte route-verdier (`/api/oppgaver/{id}`), ikke ekspanderte path-parametre
- Hver unik label-kombinasjon er en ny tidsserie: legg bare til labels som faktisk brukes i dashboards, varsler eller feilsøking

## Korrelasjons-ID i NAV-stacken

Korrelasjons-ID lar deg følge en forespørsel på tvers av tjenester, Kafka-meldinger og logger. Repoet bruker allerede `CallId`/`CallLogging` (se `kotlin-ktor`-skillen) — bygg videre på det, ikke parallelt.

### Headers
- `Nav-Callid` — NAV-konvensjon; les inn og propager på alle utgående HTTP-kall og Kafka-headere
- `X-Request-Id` / `X-Correlation-ID` — aksepter som fallback for eksterne integrasjoner
- W3C `traceparent` — settes automatisk av OpenTelemetry-agenten i NAIS

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)
    retrieveFromHeader("Nav-Callid")
    generate { UUID.randomUUID().toString() }
    verify { it.isNotBlank() }
}
install(CallLogging) {
    callIdMdc("x_request_id")
}
```

### MDC og trace-korrelasjon
Legg `callId` og `trace_id` på MDC slik at logback-encoderen automatisk får dem på alle logger i request-scope. Med OpenTelemetry-agenten kan du hente aktiv trace:

```kotlin
MDC.put("callId", call.callId)
MDC.put("trace_id", Span.current().spanContext.traceId)
```

Inkluder `trace_id`, `span_id` og `callId` i loggene slik at Loki kan korrelere med Tempo (klikkbare trace-IDer i Grafana).

## Logging og tracing

- Logg strukturert JSON til stdout — NAIS-loki henter automatisk. Ikke skriv til fil.
- Bruk `logstash-logback-encoder` med `LogstashEncoder`/`net.logstash.logback.encoder` i `logback.xml`; legg domenedata som strukturerte felt via `StructuredArguments.kv(...)`, ikke via streng-interpolasjon.
- Ikke bruk logging som erstatning for metrikker — metrikker svarer på frekvens, volum og varighet.
- Bruk tracing for request-kjeder, Kafka-flyt og kall mot Postgres eller eksterne tjenester. Aktiver OpenTelemetry auto-instrumentation i NAIS før du legger til manuelle spans.

### JSON-format for NAIS-loki

Én JSON-linje per logg på stdout. Felter Loki parser og indekserer:

```json
{
  "@timestamp": "2026-06-29T10:23:45.123Z",
  "level": "INFO",
  "message": "Oppgave behandlet",
  "logger_name": "no.nav.syfo.oppgave.OppgaveService",
  "thread_name": "eventLoopGroupProxy-4-1",
  "trace_id": "2f2f2264a8b6df9f8b3d614f4c9ce111",
  "span_id": "b3d614f4c9ce111a",
  "callId": "abc-123",
  "event_type": "oppgave_behandlet"
}
```

Minimumsfelt: `@timestamp`, `level`, `message`. Legg domenedata i top-level felt (ikke nøstet under `context`). Automatiske Loki-labels (`app`, `namespace`, `cluster`, `container`, `pod`, `stream`) skal ikke dupliseres i payloaden. Aldri fnr, aktør-id, tokens eller andre særlige kategorier personopplysninger i loggen.

## Grafana-dashboards for syfo-budstikka

Appen bør ha ett dashboard med disse panelene som baseline. Bruk `app`, `namespace` og `cluster` som template-variabler.

### Golden signals
- **Request rate** — `sum(rate(ktor_http_server_requests_seconds_count{app="syfo-budstikka"}[5m]))` (per `route`/`method`)
- **Error rate** — 5xx-andel av total trafikk, både prosent og absolutt rate
- **Latency p95/p99** — `histogram_quantile(0.95, sum(rate(ktor_http_server_requests_seconds_bucket[5m])) by (le, route))`

### Ressurser
- **Connection pool** — `hikaricp_connections_active / hikaricp_connections_max` for Postgres (krever HikariCP-binder)
- **JVM heap og GC** — `jvm_memory_used_bytes`, `rate(jvm_gc_pause_seconds_sum[5m])`
- **Pod restarts** — `increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[1h])`

### Kafka (hvis aktuelt)
- **Consumer lag** — `kafka_consumer_lag` / `kafka_consumergroup_lag` per `topic` og `consumer_group`
- **Consumer/producer rate** og feil per topic

### Domene
- Behandlede hendelser per minutt, per `event_type`
- Feilrate per flyt (`result="failure"`)
- Behandlingstid for kritiske operasjoner

Se `references/promql-logql.md` for komplette PromQL- og LogQL-eksempler.

## Varsling

- Varsle på brukeropplevde symptomer først: feilrate, latency, utilgjengelighet og pod restarts
- Bruk runbook-lenker og tydelige annotasjoner; skill mellom `warning` og `critical`
- Hold terskler forsiktige til du kjenner trafikkmønstrene — test i `dev-gcp` før du strammer i prod

Se `references/alerting.md` for Prometheus-regler og NAIS `Alert`-ressurs med Slack-ruting.

## Beslutningskandidater

Grill ikke-rutinemessige valg med `/grilling`: labels som kan øke
kardinaliteten vesentlig, produksjonsterskler, varslingskanaler som påvirker
teamets arbeidsflyt, og lagring av sensitive domenedata. Legg
observability-detalj som følger av den godkjente endringen i relevant
topic-dokument. Når et varig valg passerer ADR-gaten, anbefal dokumentert løp
og vent på brukerens valg før `/domain-modeling` registrerer det.

## Sjekkliste

- [ ] `/internal/isalive`, `/internal/isready` og scrape-path (`/internal/prometheus`) stemmer med NAIS-manifestet
- [ ] `MicrometerMetrics` installert med felles `PrometheusMeterRegistry` + JVM-binders
- [ ] OpenTelemetry auto-instrumentation vurdert/aktivert i NAIS
- [ ] Strukturert JSON-logging til stdout med `trace_id`, `span_id`, `callId`
- [ ] `Nav-Callid` leses via `CallId`, propageres på utgående kall og legges på MDC
- [ ] Viktige domenemetrikker definert med stabile `snake_case`-navn og lave-kardinalitets labels
- [ ] Dashboards dekker request rate, error rate, latency p95/p99, pool usage og Kafka lag
- [ ] Varsler finnes for høy feilrate, høy latency, pod restarts og kritiske avhengigheter
- [ ] Logger, traces og metric-labels inneholder ikke fnr, aktør-id, tokens eller andre hemmeligheter

## Boundaries

### Alltid
- Bruk `snake_case` og enhetssuffiks for metrikker
- Bruk lave og begrensede label-verdier
- Logg strukturert JSON til stdout (ikke filer)
- Propager `Nav-Callid` og legg `trace_id`/`callId` i logger via eksisterende `CallId`/`CallLogging`
- Følg eksisterende logging- og metrikkmønstre i repoet
- Verifiser health paths, scrape path og tracing-oppsett mot faktisk NAIS-konfig og `application.yaml`

### Spør først / grill
- Nye labels som kan øke kardinalitet vesentlig
- Endring av produksjonsterskler for varsler
- Nye dashboards, mapper eller varslingskanaler

### Aldri
- Logg eller eksponer fnr, aktør-id, tokens, passord eller andre særlige kategorier personopplysninger
- Bruk `camelCase` i metric-navn
- Bruk labels med høy kardinalitet (`user_id`, `fnr`, `trace_id`, `callId`)
- Legg til observability-kode som ikke kan forklares operativt eller brukes i praksis
