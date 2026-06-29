# 3-perspektiv-sjekklister — Ktor-backend (no.nav.syfo)

Sjekklistene dekker det som er NAV- og backend-spesifikt. Generisk arkitektur-/security-visdom er utelatt — bruk den fra ditt eget repertoar.

## Arkitektur

Fokus: passer endringen inn i NAVs plattform- og team-modell?

- Respekterer endringen team-autonomi? (Architecture Advice Process — søk råd, ta beslutningen selv.)
- Er berørte team (konsumenter/produsenter av data eller events) identifisert og rådspurt?
- Gjenbruker løsningen plattform-kapabiliteter framfor å bygge eget?
  - Auth → Texas/Oasis (token-utveksling/-validering) og NAVs identityprovidere, ikke egen auth-proxy.
  - Secrets → NAIS secrets / Google Secret Manager, ikke egen secret store.
  - Kafka → Aiven via NAIS (Kafkarator), ikke eget kluster.
  - Database → Cloud SQL Postgres via NAIS, ikke egen DB-server.
  - Metrics/logg/tracing → Prometheus / Loki / Tempo, ikke egen pipeline.
- Følger løsningen NAVs standardmønstre for kobling?
  - Kafka/events for asynkron kommunikasjon mellom team, REST kun der det gir mening.
  - API-kontrakter framfor delte databaser.
- Er tjenestens ansvar tydelig avgrenset (essensiell kompleksitet, ikke accidental)? Bruk slettetesten på nye lag — jf. `/improve-codebase-architecture`.
- Er det avhengigheter til on-prem eller legacy som må planlegges bort?
- Er avvik fra NAV-standard begrunnet og dokumentert i ADR-en?
- DORA-metrikker: gjør endringen deploy/lead time/change failure rate/recovery bedre eller verre?

## Sikkerhet

Fokus: NAV-spesifikk auth, `accessPolicy`, PII og personvern. Dypere trusselmodellering hører hjemme i ADR-ens risikoavsnitt eller en egen sikkerhetsgjennomgang.

- **Dataklassifisering:** Åpen / Intern / Fortrolig / Strengt fortrolig — avgjør auth- og lagringskrav.
- **Auth-mekanisme valgt riktig?** (se `/auth-overview` og `/kotlin-ktor` for Ktor-oppsett)
  - Borger-flyt (on-behalf-of sluttbruker, ID-porten-opphav) → TokenX. Valider `sub`/`pid`.
  - Intern NAV-ansatt → Azure AD. NAVident-claim identifiserer saksbehandler.
  - Tjeneste-til-tjeneste internt → TokenX eller Azure AD client credentials.
  - Ekstern virksomhet → Maskinporten.
- **`accessPolicy` i NAIS-manifest:**
  - `inbound.rules` begrenser hvem som kan kalle inn — kun nødvendige konsumenter.
  - `outbound.rules` / `outbound.external` deklarerer hvert utgående kall.
  - Least privilege — ingen wildcards der det kan unngås.
  - Auth-plugin-navnet i Ktor (`authenticate("azureAd") { }`) skal matche det `accessPolicy` faktisk slipper inn.
- **PII-beskyttelse:**
  - Ingen fødselsnummer, navn, adresse eller saksinnhold i strukturert logg — bruk callId/aktørreferanse for korrelasjon.
  - Maskering / hash der en ID må logges.
  - Kryptering i transitt (TLS) og hvile (default i Cloud SQL / bucket).
  - Kafka: aldri rå payload med PII i logg.
- **Personvern og regulatoriske krav:**
  - Ny eller endret behandling av personopplysninger? → vurder DPIA (personvernkonsekvensvurdering).
  - Kontakt personvernombud og / eller behandlingsansvarlig ved usikkerhet.
  - Er behandlingsgrunnlag dokumentert (lovhjemmel, samtykke, etc.)?
  - Melding til Datatilsynet nødvendig? (ved høy risiko tross tiltak).
- **Secrets:** ingen secrets i kode, logg eller ADR — bruk NAIS secrets / Google Secret Manager.
- **Audit-spor:** er sensitive operasjoner sporet et sted man faktisk kan søke i?

## Plattform

Fokus: NAIS/GCP-konsekvenser, observerbarhet, CI/CD. Manifestdetaljer hører til `/nais-manifest`.

- **NAIS-manifest:**
  - `resources.requests` satt realistisk (CPU + minne) for en JVM/Netty-prosess.
  - **Ikke** sett `resources.limits.cpu` — gir throttling på NAIS.
  - `resources.limits.memory` satt for å fange lekkasjer; aksepter OOM framfor trege pods.
  - `replicas.min` / `replicas.max` passer trafikk og HA-behov.
  - `liveness`/`readiness`-probes konfigurert (readiness feiler før liveness under oppstart). Ktor/Netty håndterer SIGTERM og graceful shutdown selv — ikke vipp readiness manuelt.
- **Plattform-tjenester:**
  - Cloud SQL: instanstype, disk, backup, `flags` — og Flyway-migreringer (append-only, kjøres ved oppstart; jf. `/flyway-migration`, `/postgresql-review`).
  - Kafka: topic-er deklarert i Kafkarator, pool valgt (`nav-prod`, `nav-dev`, etc.), retention satt; konsumenter idempotente og replay-tålende (jf. `/kafka-topic`).
  - GCS bucket / andre — deklarert i manifest der mulig.
- **Observerbarhet fra dag 1:**
  - Prometheus-metrikker: forretning (domeneteller) + teknisk (HTTP, kø-lag, DB-pool).
  - Strukturert JSON-logg via Logback/Logstash-encoder til stdout, ingen PII.
  - Distribuert tracing (OpenTelemetry) — auto-instrumentering der mulig.
  - Alerts med meningsfulle terskler og runbook-lenke.
- **CI/CD:**
  - Standard GitHub Actions-workflow (build → test → deploy til dev → deploy til prod). Kvalitetsgater er deterministiske: `./gradlew test`, `./gradlew build`.
  - Deploy-strategi (rolling / feature toggle / parallell drift) passer endringen.
  - Rollback-sti tydelig (revert + re-deploy, eller `kubectl rollout undo` som siste utvei).
- **Kostnad og kvote:**
  - Ressursbehov innenfor team-kvote i GCP-prosjektet?
  - Data-egress, lagring og Cloud SQL-instanser er de største kostnadsdriverne — gjør et grovt estimat.
- **Drift og beredskap:**
  - Runbook for vanlige feilmoduser (drift-diagnose: `/diagnosing-bugs`).
  - Eier og vakt-ansvar tydelig før produksjonssetting.
