# Budstikka

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Ktor](https://img.shields.io/badge/Ktor-087CFA?logo=ktor&logoColor=white)](https://ktor.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-18-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Gradle](https://img.shields.io/badge/Gradle-02303A?logo=gradle&logoColor=white)](https://gradle.org/)
[![Java](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/)

## Formål

Budstikka er en Ktor-backend for å håndtere kommunikasjon fra våre apper til flere eksterne og interne kanaler.

## Big picture

```mermaid
sequenceDiagram
    autonumber
    participant producer as Domeneapp
    participant kafka as Kafka-topic team-esyfo.budstikka.v1
    participant consumer as Inbox-consumer<br/>(ConsumerRunner + InboxMessageHandler)
    participant inbox as inbox_message (db)
    participant iworker as InboxMessageWorker
    participant decision as Decision + effectuate<br/>(in-process komponent)
    participant delivery as delivery (db)
    participant dworker as DeliveryWorker
    participant channel as Channel<br/>(via ChannelHandler)
    participant target as Channel endpoint

    producer->>kafka: publish Dispatch(eventId, reference, content)
    consumer->>kafka: consume records
    consumer->>inbox: saveBatch (batchInsert, dedup på eventId)
    iworker->>inbox: claim(limit, lease)
    iworker->>decision: process(dispatch) + effectuate(eventId, decision)
    decision->>inbox: markProcessed/markDropped/markFailed
    decision->>delivery: saveInTransaction(...) ved Processed
    dworker->>delivery: claim(limit, lease, channels)
    dworker->>channel: deliver(claimed delivery)
    channel->>target: send
    channel-->>dworker: Sent | Failed(reason)
    dworker->>delivery: markSent | markFailed
```

## Beslutningsmønster

Beslutningsmotoren er en in-process komponent som kalles fra `InboxMessageWorker`, ikke en egen worker/task. Figuren viser den som én boks (`Decision + effectuate`) for å holde hovedflyten enkel.

I kode er den delt i `DecisionProcess` og `EffectuateDecision`, og kjører i to steg:

1. `DecisionRule.resolve(event)` henter grunnlag i parallell.
2. `ResolvedRule.apply(deliveries)` foldes sekvensielt, med short-circuit ved `Dropped`/`Failed`.

Dette gir lavere ventetid på oppslag og samtidig forutsigbar regelrekkefølge.

## Arkitekturoversikt

Se [overordnet flyt](docs/flyt.md) for claim/lease, batch insert, kanal-mapping og flere detaljer.

## Kjøre lokalt

Forutsetninger: [mise](https://mise.jdx.dev/) og en container-runtime (Docker eller podman) som kjører. `mise` gir deg riktig Java-versjon og oppgavene under.

Det finnes to måter å kjøre appen lokalt på.

### Testcontainers (enklest, uten compose)

```sh
mise dev:tc       # eller: ./gradlew runLocal
```

Dette booter hele appen mot Testcontainers: Postgres og Kafka startes fra kode, eksterne integrasjoner (for eksempel PDL) byttes mot fakes, og en Kafka UI startes i nettleseren for å inspisere topics, meldinger, konsumentgrupper og offsets. Ved oppstart logges Kafka-bootstrap, formidling-topic, JDBC-URL og Kafka UI-URL for live-inspeksjon. Avslutt med Ctrl+C, som river ned containerne.

Løpet trenger ikke docker-compose-infraen og henter ikke tokens. Det bruker samme test-substrat som e2e-testene. Se `docs/TESTSTRATEGI.md` for detaljer.

Loggene i det lokale løpet er menneskelig lesbar tekst (ikke JSON) via `src/test/resources/logback-local.xml`. Prod logger fortsatt strukturert JSON.

### Docker-compose (ekte adaptere)

```sh
mise run go           # starter infra + kjører appen (./gradlew run)
mise run infra        # starter bare Postgres + Kafka + Kafka UI
mise run infra:down   # stopper infraen
```

`mise run go` kjører appen mot compose-infraen med de ekte adapterne. Miljøvariablene (DB, Kafka) leses fra `mise.toml`.

### IntelliJ

1. Åpne prosjektet som et Gradle-prosjekt og sett prosjekt-JDK til Java 25.
2. For Testcontainers-løpet: kjør Gradle-oppgaven `runLocal` (under `application` i Gradle-vinduet), eller åpne `src/test/kotlin/no/nav/budstikka/LocalApp.kt` og kjør `main()` direkte fra kjør-knappen i margen. Dette løpet er selvstendig og trenger ingen miljøvariabler.
3. For compose-løpet: start infraen med `mise run infra` først, og kjør deretter Gradle-oppgaven `run`. Sett da miljøvariablene fra `mise.toml` i kjørekonfigurasjonen (IntelliJ leser dem ikke automatisk fra mise).

## Testing

```sh
mise run test         # enhets- og integrasjonstester (rask, e2e ekskludert)
mise run test:e2e     # opt-in full-boot e2e mot Testcontainers
mise run lint         # ktlintCheck
```

E2e-testene er tagget `E2E` og kobles ikke til `test` eller `build`, så deploy-løpet slipper å vente på dem. Kjør dem lokalt eller i en egen jobb ved behov.

## For Nav-ansatte

Spørsmål om tjenesten kan tas i [#esyfo på Slack](https://nav-it.slack.com/archives/C012X796B4L).
