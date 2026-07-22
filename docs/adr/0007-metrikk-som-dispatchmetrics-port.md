# ADR 0007 — Metrikk-oppsett: DispatchMetrics-port + Micrometer-bindere

- Status: besluttet (issue #28 metrics-only slice, #41 consumer-lag metric)
- Dato: 2026-07-14
- Relatert: B45–B49 i `docs/context.md` (observability), B57 (erstatter B47s metrikk-navn),
  ADR 0003 (application-lag/porter), ADR 0004 (konkurrerende konsumenter), issue #28 og #41

## Kontekst

Issue #28 (observability) og #41 (consumer-lag-alert) deler én kjerne: å eksponere de riktige
Prometheus-metrikkene fra koden. Grunnlaget fantes allerede — et delt `PrometheusMeterRegistry`,
`/internal/metrics`-scrape, JSON-logg (logstash) og `CallId` — men det manglet:

- HTTP- og JVM-metrikker (ingen `MicrometerMetrics`-plugin, ingen JVM-bindere),
- Kafka-klientmetrikker (lag), som #41 trenger (`records-lag-max`),
- domene-/worker-metrikker for inbox → beslutning → leveranse.

Beslutningskjernen (`domain`) og use case-workerne (`application`) skal ALDRI avhenge av
infrastructure (ADR 0003). Micrometer er en infrastruktur-bekymring, så domenemetrikkene kan ikke
emitteres ved å importere Micrometer rett inn i workerne uten å bryte den grensen.

Denne ADR-en dekker kun metrikker. Tracing (OTel), strukturert logg-utvidelse, Grafana-dashboard og
NAIS-alerts er bevisst utsatt til egne ADR-er (alerts/dashboard krever dessuten et NAIS app-manifest
som ennå ikke finnes).

## Beslutning

Tre lag, hvert med sitt tydelige ansvar:

1. **Metrics-wiring i `infrastructure` (ingen app-endring).** `MicrometerMetrics`-pluginen installeres på det
   delte registeret med JVM-/prosess-metrics → `ktor_http_server_requests_seconds_*` + `jvm_*`.
   `KafkaClientMetrics` wired per consumer → `kafka_consumer_*` inkl.
   `kafka_consumer_fetch_manager_records_lag_max` (metrikken #41 bygger på). Consumeren bygges på
   nytt ved hver transient restart (ADR 0004-runner), så wiring lukkes før rebind for å unngå at
   døde tidsserier (ny auto-generert `client.id`) akkumuleres.

2. **Worker-livssyklus i `BackgroundLoop` (infrastruktur).** Løkka eier allerede runde-livssyklusen,
   så `worker_runs_total` / `worker_duration_seconds` / `worker_failures_total` (tagget `worker`)
   måles generisk der, uten å røre `application`-workerne. Registeret injiseres nullbart — null gir
   ingen-op i enhetstester.

3. **Domenemetrikker via `DispatchMetrics`-port (`application`).** En tynn port sender ut
   inbox- og leveranse-hendelser; Micrometer-adapteren (`MicrometerDispatchMetrics`) i
   infrastructure teller dem på det delte registeret, wired i bootstrap. Samme port/adapter-mønster som
   `TransactionRunner` og repositoriene — workerne holdes fri for Micrometer-import og forblir
   testbare med en opptaks-fake.

Navn er engelske og følger Prometheus-konvensjon (`snake_case`, `_total`/`_seconds`), jf. B57:
`inbox_message_claimed_total`, `inbox_message_empty_polls_total`, `inbox_message_processed_total`,
`inbox_message_dropped_total{reason}`, `inbox_message_failed_total`, `delivery_claimed_total`,
`delivery_empty_polls_total`, `delivery_total{channel,result}`. Labels er lav-kardinale og PII-frie
(kanal-navn + faste utfall) — aldri `eventId`, fnr eller andre personopplysninger (B45/B46).

## Konsekvenser

- #41 sin metrikk-side er dekket: lag eksponeres på `/internal/metrics`. Selve alerten (NAIS
  `PrometheusRule`) gjenstår og krever app-manifestet.
- Tellingen skjer på replicaens beslutning/leveranse. I et lease-kappløp (ADR 0004) kan en taper
  telle et utfall uten å skrive rad — akseptert støy for observerbarhet, ikke en regnskapskilde.
- En ny kanal får `delivery_total{channel}` gratis via `Channel`-enumet; en ny domenemetrikk er én
  port-metode + adapter-linje.
