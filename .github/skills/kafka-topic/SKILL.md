---
name: kafka-topic
description: "Brukes når dette Ktor-backendet (no.nav.syfo) skal produsere eller konsumere Kafka-hendelser — ny eller endret consumer/producer, ny topic via Kafkarator, event-kontrakt, key-strategi, idempotens eller DLQ. Triggere: 'lytte på topic', 'publisere hendelse', 'Kafkarator', 'Topic-CRD', 'consumer', 'producer', 'Rapids & Rivers', 'River', '@event_name', 'kafka.pool', 'DLQ', 'idempotent konsument'."
---

# Kafka — topic, consumer og producer

Nav-spesifikke konvensjoner for Kafka i dette repoet. Generell Kafka-teori er ikke dekket — fokus er topic-provisjonering, event-kontrakt og hvordan consumer/producer kobles inn i en Ktor-app.

Brukes typisk i @grillmester fase 1–2 når en event-kontrakt formes (loggføres som ADR), og i implementasjonsfasen når en consumer eller producer skrives.

## Detekter eksisterende Kafka-stil først

**Før du foreslår kode: finn ut hvilken stack repoet allerede bruker. Følg det. Ikke introduser en ny stil eller migrer mellom dem uten eksplisitt oppdrag.**

I et Ktor-backend er det to realistiske valg:

| Signal i `build.gradle.kts` / kode | Stack |
|------------------------------------|-------|
| `no.nav.helse:rapids-rivers`; `RapidApplication.create(env)`; klasser som implementerer `River.PacketListener` | Rapids & Rivers |
| `org.apache.kafka:kafka-clients`; direkte `KafkaConsumer` / `KafkaProducer`, ofte i en egen tråd/coroutine ved siden av Ktor-serveren | Plain Apache Kafka |

Spring Kafka (`@KafkaListener`, `KafkaTemplate`) hører ikke hjemme i en Ktor-app — ser du det, er repoet enten ikke et Ktor-repo eller bruker feil mønster. Ikke foreslå Spring Kafka her.

Følg det dominerende mønsteret. Hvis repoet ikke har Kafka fra før, velg plain Apache Kafka med mindre teamet allerede står på Rapids i nabotjenester.

## Tilnærming

1. Sjekk Nais-manifestet for `kafka.pool` og om Kafkarator `Topic`-CRD-er finnes (ofte i et eget `<team>-kafka`-repo).
2. Søk i kodebasen etter eksisterende consumere/producere og følg samme mønster (oppstart, feilhåndtering, logging).
3. Bekreft stack i `build.gradle.kts` (se tabell over).
4. Planlegg event-kontrakt (topic-navn, key, felter, `@event_name`). Kontrakten er et domenevalg — loggfør den som ADR i `docs/adr/` og oppdater `docs/context.md` hvis hendelsen blir en del av domenespråket (jf. `/grill-with-docs`).
5. Implementer etter mønsteret for stacken (se referansefilene under).
6. Verifiser med tester (se referansefilene) og loggfør i `.grill/VERIFICATION.md` (@grillmester fase 5).

## Sync vs. hendelse — når velge hva

| Behov | Mønster | Når |
|-------|---------|-----|
| Svar trengs umiddelbart, kallet må lykkes/feile synlig | REST på Ktor-route (se `/api-design`) | CRUD, oppslag, brukerinteraksjon |
| Fire-and-forget notifikasjon, audit, asynk nedstrøm | Kafka-producer (plain) | Varsling, logg, prosess som kan vente |
| Hendelse-koreografi på tvers av mange tjenester | Rapids & Rivers på delt rapid-topic | Saga-flyt, flertjeneste-arbeidsflyt |
| Periodisk batch | Naisjob (+ Kafka hvis nedstrøm) | Nattjobber, rapporter, reprosessering |

Bruker teamet allerede Rapids for koreografi, publiser nye hendelser dit — ikke lag en parallell plain-producer.

## Kafka i en Ktor-app — hvor kjører consumeren?

Ktor-serveren (`EngineMain` på Netty) og Kafka-consumeren er to uavhengige livssykluser i samme prosess. En `KafkaConsumer.poll`-løkke må kjøre ved siden av HTTP-serveren, ikke inne i en request. Start og stopp den sammen med applikasjonen:

```kotlin
fun Application.kafkaConsumerModule(consumer: HendelseConsumer) {
    val job = launch(Dispatchers.IO) { consumer.run() }   // egen coroutine, ikke i en route
    monitor.subscribe(ApplicationStopPreparing) {
        consumer.stop()                                    // sett running=false, la løkka avslutte
        job.cancel()
    }
}
```

Eksponer consumer-helsen i `/internal/isready` slik at podden ikke markeres klar før consumeren faktisk poller. Hold `/internal/*` (isalive, isready, metrics) utenfor auth, jf. `/auth-overview`.

## NAIS Kafka-konfigurasjon

```yaml
# nais/*.yaml (utdrag)
spec:
  kafka:
    pool: nav-dev   # eller nav-prod
```

NAIS injiserer SSL-env i podden — les dem i Ktor via `System.getenv(...)` eller `environment.config`:

- `KAFKA_BROKERS` — bootstrap servers
- `KAFKA_TRUSTSTORE_PATH` / `KAFKA_KEYSTORE_PATH` — PKCS12-filer
- `KAFKA_CREDSTORE_PASSWORD` — passord for begge
- `KAFKA_SCHEMA_REGISTRY*` — kun hvis schema registry er aktivert

## Topic-provisjonering med Kafkarator

Topics i Nav opprettes deklarativt via Kafkarator `Topic`-CRD-er — ikke via kode eller `kubectl` manuelt.

```yaml
apiVersion: kafka.nais.io/v1
kind: Topic
metadata:
  name: <team>.<domene>.v<versjon>
  namespace: <team>
  labels:
    team: <team>
spec:
  pool: nav-prod
  config:
    retentionHours: 168          # 7 dager
    retentionBytes: -1           # uten grense
    cleanupPolicy: delete        # eller "compact" for state-topics
    minimumInSyncReplicas: 2
    partitions: 3
    replication: 3
  acl:
    - team: <team>
      application: <app>
      access: readwrite          # read | write | readwrite
    - team: <annet-team>
      application: <konsument-app>
      access: read
```

Viktige valg:

- **cleanupPolicy: compact** for topics som representerer siste tilstand per nøkkel. Krever stabil key.
- **partitions**: øk tidlig — nedjustering krever ny topic. Start 3–6 for domene-hendelser.
- **acl**: eksplisitt per konsument-app — ikke wildcard.

## Nav-hendelsesdesign (stack-agnostisk)

Gjelder uansett om du bruker plain Kafka eller Rapids.

### Topic-navngivning

```
<team>.<domene>.v<versjon>

teamsykefravar.rapid.v1            # Rapids & Rivers fellestopic
teamsykefravar.sykmelding.v1       # Domene-hendelser
teamsykefravar.oppfolging.v1       # Domene-hendelser
```

### Key-strategi

- **Bruker/entitet-ID som key** → hendelser for samme entitet havner på samme partisjon → rekkefølge bevares per entitet.
- `fnr`, `aktørId`, `sykmeldingId`, `vedtakId` er typiske nøkler.
- Ikke bruk random UUID som key med mindre du bevisst vil ha jevn partisjonsspredning uten rekkefølge-garanti.

### Event-navngivning og innhold

- **Fortid + snake_case**: `sykmelding_sendt`, `oppfolging_opprettet`, `vedtak_fattet` — ikke `create_x` / `process`.
- **Hendelser er fakta**, ikke kommandoer. Beskriv hva som skjedde.
- **Standard metadata** i payload:
  - `@event_name` — hendelsestype
  - `@id` — unik UUID per hendelse (brukes til idempotens)
  - `@created_at` — ISO-8601 timestamp
  - `@produced_by` — avsendertjeneste
  - `@correlation_id` — propagér fra innkommende request (sterkt anbefalt)
- **Ingen PII uten bevisst vurdering.** Fødselsnummer som key er akseptabelt på Nav-interne topics, men aldri logg det, og vurder kryptering av sensitive fritekstfelter.

## Idempotens

Kafka leverer minst-én-gang — duplikater forekommer. Konsumenter må være idempotente. Dedup på en stabil event-ID (`@id` i payload), aldri på Kafka-offset (endres ved re-partisjonering).

```kotlin
fun prosesser(eventId: String, /* ... */) {
    if (eventStore.alleredeProsessert(eventId)) return
    // prosesser ...
    eventStore.markerProsessert(eventId)
}
```

Dedup-tabellen er typisk en Postgres-tabell — legg den inn som Flyway-migrering (`/flyway-migration`).

## Dead-letter-håndtering (konsept)

Meldinger som aldri kan prosesseres (korrupt payload, permanent valideringsfeil) skal **ikke blokkere strømmen**.

1. **Skill midlertidig vs. permanent feil.** Midlertidig (nettverk, DB nede) → kast exception, la Kafka retry. Permanent → log + DLQ, fortsett.
2. **DLQ-topic** per domene (`<team>.<domene>.dlq.v1`) med originalmelding + feilårsak + timestamp.
3. **Alarm på DLQ-rate**, ikke på enkeltmelding.
4. **Manuell gjenspilling** etter bugfix: les DLQ, republiser til original topic.

Implementasjonen følger stacken — i et Ktor-repo ruller man som regel en egen liten DLQ-producer. Følg mønsteret som allerede finnes.

## Event-evolusjon

```
Hvordan endre en eksisterende hendelse?
├── Legg til nytt felt (optional)
│   └── Bakoverkompatibelt. Konsumenter må tolerere ukjente felter
│       (tolerant parsing / interestedIn), ikke kreve dem.
│
├── Endre feltformat (breaking)
│   └── Ny topic-versjon v2. Dual-write fra produsent.
│       Migrer konsumenter én om gangen. Stopp v1-produksjon sist.
│
├── Fjerne felt
│   └── 1. Verifiser at ingen konsument krever feltet.
│       2. Fjern fra produsent. 3. Vent + overvåk før topic-rydding.
│
└── Ny hendelsestype
    └── Publiser med ny @event_name. Eksisterende konsumenter ignorerer
        ukjente event_names (gjelder spesielt Rapids).
```

Breaking event-endringer er et koordineringsproblem med konsumerende team — samme disiplin som API-versjonering (`/api-design`). Loggfør beslutningen som ADR i `docs/adr/`.

## Stack-spesifikke mønstre

Les kun den som er aktuell for repoet:

- **Plain Apache Kafka** (Kotlin consumer/producer i Ktor, SSL-config, commit-strategi, Testcontainers): [`references/plain-kafka.md`](references/plain-kafka.md).
- **Rapids & Rivers** (River-oppsett, validate/demand/require, publisering, TestRapid): [`references/rapids-and-rivers.md`](references/rapids-and-rivers.md).

## Grenser

### Alltid
- Følg stacken repoet allerede bruker.
- Opprett topics via Kafkarator `Topic`-CRD — aldri ad-hoc i kode/`kubectl`.
- Eksplisitt ACL per konsument-app.
- Topic-navn `<team>.<domene>.v<versjon>`; event-navn i fortid + snake_case.
- Idempotent konsumering (dedup på `@id`).
- DLQ for permanente feil, alarm på DLQ-rate.
- Strukturert logging med `event_id` / `correlation_id` — aldri PII (`fnr`) i logg.
- `kafka.pool` satt i Nais-manifest før deploy.

### Spør først
- Migrasjon plain ↔ Rapids.
- Endre `KAFKA_CONSUMER_GROUP_ID` / consumer-group (utløser reprosessering fra `auto.offset.reset`).
- Breaking event-endring som andre team konsumerer.
- Endre `partitions` / `cleanupPolicy` på eksisterende topic.

### Aldri
- Logge fødselsnummer eller annen PII.
- Bruke Kafka-offset som idempotens-nøkkel.
- Kjøre `poll`-løkka inne i en HTTP-handler.
- Sluke permanente feil stille slik at strømmen stopper.

NAIS-dok: https://doc.nais.io/persistence/kafka/
