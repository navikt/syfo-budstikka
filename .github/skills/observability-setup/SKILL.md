---
name: observability-setup
description: "Bruk ved etablering eller forbedring av observability i syfo-budstikka: Micrometer/Prometheus i Ktor, konfigurerte health- og scrape-ruter, strukturert JSON-logging med event_id/reference og OTel trace-felt, PromQL/LogQL, Grafana-dashboards og Prometheus-alerts i NAIS — eller når noen sier /observability-setup."
---

# Observability i syfo-budstikka

Ktor 3.x på Netty, kildepakke `no.nav.budstikka`, kjører i NAIS. Hold
hovedreglene her korte — bruk `references/` for fullstendige eksempler.

- **Metrikker** forteller *hva* som skjer
- **Logger** forklarer *hvorfor* det skjedde
- **Traces** viser *hvor* i flyten det skjedde
- Verifiser alltid eksisterende oppsett i repoet før du legger til nye målepunkter, labels eller varsler

## Arbeidsflyt

1. Les NAIS-manifestet, aktiv applikasjonskonfigurasjon, `logback.xml` og
   `build.gradle.kts`/`gradle/libs.versions.toml` for eksisterende oppsett.
2. Finn etablerte mønstre for `MicrometerMetrics`, Ktor DI, `MdcKeys`,
   strukturerte loggfelter og health-ruter.
3. Verifiser health- og scrape-stiene mot både `InternalApi` og NAIS-manifestet;
   kode og manifest må være identiske.
4. Start med standardmetrikker (Ktor HTTP-server + JVM) og utvid med domenemetrikker som gir operativ verdi.
5. Legg til dashboards og varsler først når metrikkene og label-settet er stabile.

## Metrikker i Ktor (Micrometer)

Ktor har ingen Actuator. Opprett ett `PrometheusMeterRegistry`, installer
`MicrometerMetrics`, eksponer samme registry på den konfigurerte interne ruten,
og del det via repoets eksisterende Ktor DI.

```kotlin
val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

install(MicrometerMetrics) {
    this.registry = registry
    // ktor_http_server_requests_seconds_* med route/method/status som tags
    meterBinders = listOf(
        JvmMemoryMetrics(),
        JvmGcMetrics(),
        ProcessorMetrics(),
    )
}

routing {
    get("/internal/metrics") { call.respond(registry.scrape()) }
    get("/internal/health/is_alive") { call.respondText("OK") }
    get("/internal/health/is_ready") { call.respondText("OK") }
}
```

- `MicrometerMetrics` gir automatisk `ktor_http_server_requests_seconds` (count/sum/bucket) med tags for route, method og status.
- Bruk `distributionStatisticConfig` med `percentilesHistogram(true)` hvis du trenger p95/p99 fra Prometheus.
- Behold repoets etablerte liveness- og readiness-semantikk. Endre ikke hvilke
  avhengigheter de måler uten en eksplisitt beslutning; Kafka-helse kan for
  eksempel eies av consumer-lag i stedet for readiness.

Se `references/micrometer.md` for `Counter`/`Timer`/`Gauge`/
`DistributionSummary`, registry-wiring, domene- og Kafka-metrikker.

## Navngivning for metrikker og labels

### Metrikker
- Bruk `snake_case` (Prometheus-konvensjon; Micrometer punkt-navn blir automatisk `snake_case`)
- Bruk enhetssuffiks når det er relevant: `_seconds`, `_bytes`
- Countere skal ha suffikset `_total`
- Bruk navn som beskriver domenet, ikke `camelCase` eller miljøspesifikke navn

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

## Korrelasjonsmodell

Les B46, B58 og B59 i `docs/decisions.md` før du endrer korrelasjon:

- `event_id` er den persisterte korrelasjonen gjennom én asynkron eventflyt.
- `reference` korrelerer relaterte events på tvers av opprett/ferdigstill.
- W3C `traceparent` og `trace_id`/`span_id` dekker hvert tekniske hopp via
  NAIS OpenTelemetry-agenten.
- En request-ID brukes bare når en etablert synkron kontrakt krever den. Den
  propageres ikke i Kafka og presenteres aldri som ende-til-ende uten persistens.

### MDC og trace-korrelasjon

Fest persisterte forretnings-ID-er på MDC i hvert prosesseringssteg og rydd dem
etterpå. Bruk `MDCContext` over suspenderingspunkter. OTel-agenten legger
trace-feltene på MDC; ikke lag en parallell manuell modell.

```kotlin
MDC.putCloseable(MdcKeys.EVENT_ID, eventId.toString()).use {
    withContext(MDCContext()) { process() }
}
```

Logstash-encoderen tar med `event_id`, `reference`, `trace_id` og `span_id` når
de finnes, slik at Loki kan korreleres med Tempo uten å blande forretnings- og
trace-identitet.

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
  "logger_name": "no.nav.budstikka.application.DeliveryWorker",
  "thread_name": "eventLoopGroupProxy-4-1",
  "trace_id": "2f2f2264a8b6df9f8b3d614f4c9ce111",
  "span_id": "b3d614f4c9ce111a",
  "event_id": "8d4e0fd3-8f26-4f93-9585-67f7aa80df86",
  "reference": "technical-reference",
  "event_type": "delivery_sent"
}
```

Minimumsfelt: `@timestamp`, `level`, `message`. Legg domenedata i top-level felt (ikke nøstet under `context`). Automatiske Loki-labels (`app`, `namespace`, `cluster`, `container`, `pod`, `stream`) skal ikke dupliseres i payloaden. Aldri fnr, aktør-id, tokens eller andre særlige kategorier personopplysninger i loggen.

## Grafana-dashboards for syfo-budstikka

Les det eksisterende dashboardet før du endrer paneler. Bruk `app`, `namespace`
og `cluster` som template-variabler.

### Domene- og workerflyt
- Inbox behandlet, droppet og feilet
- Leveranser per kanal, resultat og drop-årsak
- Worker runs, failures, varighet og empty-poll-ratio
- Meldingskorrelasjon på `event_id` og `reference`

### Ressurser
- **Connection pool** — `hikaricp_connections_active / hikaricp_connections_max` for Postgres (krever HikariCP-binder)
- **JVM heap og GC** — `jvm_memory_used_bytes`, `rate(jvm_gc_pause_seconds_sum[5m])`
- **Pod restarts** — `increase(kube_pod_container_status_restarts_total{app="syfo-budstikka"}[1h])`

### Kafka (hvis aktuelt)
- **Consumer lag** — `kafka_consumer_lag` / `kafka_consumergroup_lag` per `topic` og `consumer_group`
- **Consumer/producer rate** og feil per topic

Se `references/promql-logql.md` for generell PromQL-/LogQL-syntaks; faktisk
kode og dashboard eier metrikk- og feltnavnene.

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

- [ ] Health- og scrape-stiene i kode stemmer med NAIS-manifestet
- [ ] `MicrometerMetrics` installert med felles `PrometheusMeterRegistry` + JVM-binders
- [ ] OpenTelemetry auto-instrumentation vurdert/aktivert i NAIS
- [ ] Strukturert JSON-logging til stdout med trygge `event_id`/`reference`-felt og OTel `trace_id`/`span_id`
- [ ] Korrelasjon følger B46/B58/B59; ingen request-ID fremstilles som ende-til-ende over asynkrone grenser
- [ ] Viktige domenemetrikker definert med stabile `snake_case`-navn og lave-kardinalitets labels
- [ ] Dashboard dekker domeneflyt, worker-helse, Kafka-lag, feillogger og meldingskorrelasjon
- [ ] Varsler finnes for høy feilrate, høy latency, pod restarts og kritiske avhengigheter
- [ ] Logger, traces og metric-labels inneholder ikke fnr, aktør-id, tokens eller andre hemmeligheter

## Boundaries

### Alltid
- Bruk `snake_case` og enhetssuffiks for metrikker
- Bruk lave og begrensede label-verdier
- Logg strukturert JSON til stdout (ikke filer)
- Følg repoets persisterte korrelasjonsmodell og OTel-felt per hopp
- Følg eksisterende logging- og metrikkmønstre i repoet
- Verifiser health paths, scrape path og tracing-oppsett mot faktisk NAIS- og applikasjonskonfigurasjon

### Spør først / grill
- Nye labels som kan øke kardinalitet vesentlig
- Endring av produksjonsterskler for varsler
- Nye dashboards, mapper eller varslingskanaler

### Aldri
- Logg eller eksponer fnr, aktør-id, tokens, passord eller andre særlige kategorier personopplysninger
- Bruk `camelCase` i metric-navn
- Bruk labels med høy kardinalitet (`user_id`, `fnr`, `trace_id`, `callId`)
- Legg til observability-kode som ikke kan forklares operativt eller brukes i praksis
