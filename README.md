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

## For Nav-ansatte

Spørsmål om tjenesten kan tas i [#esyfo på Slack](https://nav-it.slack.com/archives/C012X796B4L).
