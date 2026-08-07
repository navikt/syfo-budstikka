# Budstikka – send varsler fra sykefraværsappene

Budstikka gir sykefraværsappene en felles Kafka-kontrakt for å sende varsler. Appen bygger en
ferdig `EncodedDispatch` med fasaden og publiserer den med sin egen Kafka-producer.

## Slik sender du varsel

Legg Nav sitt GitHub Packages-speil og kontrakten i produsentens Gradle-bygg. Kontrakten inneholder
ikke `kafka-clients`; appen eier selv Kafka-versjon, producer-konfigurasjon og livsløp.

```kotlin
repositories {
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    mavenCentral()
}

dependencies {
    implementation("no.nav.syfo:budstikka-kontrakt:<latest release version>")
    implementation("org.apache.kafka:kafka-clients:4.3.1")
}
```

Produsenten trenger minst Kotlin 2.3 og Java 21, samt Kafka-tilgang. [Producerguiden](docs/sende-varsler.md)
beskriver påkrevd `kafka.pool` og `write` ACL.

Opprett og lagre `EventId` sammen med eget arbeid **før** første sending. Ved retry eller recovery
leser du den lagrede id-en og bruker den på nytt. Opprettelsen må være atomisk; en vanlig
load-then-create-sekvens er ikke nok når flere workers kan behandle samme arbeid.

```kotlin
import no.nav.budstikka.contract.Budstikka
import no.nav.budstikka.contract.EventId
import no.nav.budstikka.contract.PersonIdentifier
import no.nav.budstikka.contract.Varseltype
import org.apache.kafka.clients.producer.ProducerRecord

// Pseudokode: opprett og lagre EventId atomisk i egen lagring, og les samme id ved retry.
val eventId = eventIdStore.loadOrCreateAtomically(notificationId) { EventId.new() }

val encoded = Budstikka.brukervarselCreate(
    eventId = eventId,
    reference = "synthetic-reference-0001",
    sykmeldt = PersonIdentifier("00000000000"),
    varseltype = Varseltype.BESKJED,
    text = "SYNTETISK-VARSELTEKST",
)

val record = ProducerRecord(encoded.topic, encoded.key, encoded.value)
encoded.headerBytes().forEach { (name, value) -> record.headers().add(name, value) }
kafkaProducer.send(record)
```

`encoded.key` er mottakerens partisjonsnøkkel. `eventId` ligger bare i headeren og brukes til
deduplisering. Ikke logg nøkkel, payload eller varseltekst.

Se [producerguiden](docs/sende-varsler.md) for støttede funksjoner, retry og versjonering.

## Arkitektur

Budstikka konsumerer kontrakten fra Kafka, lagrer meldingen og sender den videre til riktig kanal.
Den interne flyten eies av [docs/flyt.md](docs/flyt.md).

Øvrig vedlikeholdt dokumentasjon:

- [Datamodell](docs/datamodell.md) — inbox, delivery, dead letter, claim/lease og tilstander
- [Migrering](docs/migrering.md) — kildefestet cutover-plan fra esyfovarsel
- [Teststrategi](docs/teststrategi.md) — delt testsubstrat, e2e og lokal kjøring
- [Helsesjekk](docs/helsesjekk.md) — liveness-kontrakten for Kafka-consumeren
- [Dead-letter-replay](docs/dead-letter-replay.md) — manuell replay-prosedyre

## Kjøre lokalt

Forutsetninger: [mise](https://mise.jdx.dev/) og en container-runtime (Docker eller podman).

```sh
mise dev:tc       # eller: ./gradlew runLocal
```

Løpet starter appen mot Testcontainers med Postgres, Kafka og fakes for eksterne integrasjoner.
Se [teststrategien](docs/teststrategi.md) for testnivåer og lokal kjøring. Kjør `./gradlew tasks`
for tilgjengelige Gradle-oppgaver.

## For Nav-ansatte

Spørsmål om tjenesten kan tas i [#esyfo på Slack](https://nav-it.slack.com/archives/C012X796B4L).
