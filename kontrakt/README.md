# budstikka-kontrakt

Delt kontraktbibliotek for å sende meldinger til **syfo-budstikka**. Produsent-apper kompilerer mot
dette biblioteket og får typede meldinger, en Kotlin DSL og konsistent serialisering — uten å arve
budstikkas infrastruktur (ingen Ktor/Exposed/Kafka-avhengigheter følger med).

- Artefakt: `no.nav.syfo:budstikka-kontrakt`
- Avhenger kun av `kotlinx-serialization-json` (transitivt) + Kotlin stdlib
- Bygget mot JVM 21 (fungerer på JDK 21 og nyere)

## Legg til avhengigheten

Biblioteket ligger på GitHub Packages og hentes via NAVs mirror (samme som resten av `no.nav`).

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    }
}
```

`build.gradle.kts`:

```kotlin
dependencies {
    implementation("no.nav.syfo:budstikka-kontrakt:<versjon>")
}
```

Versjonen følger semver. **`0.x` fram til budstikka er i prod** — i denne fasen kan en minor-bump
være breaking (nye/endrede meldingstyper). Fra `1.0.0` gjelder streng semver.

## Slik sender du en melding

DSL-en bygger og serialiserer meldingen og returnerer et `EncodedDispatch` med alt du trenger for å
legge den på Kafka. Budstikka eier ingen producer-klient — du publiserer selv:

```kotlin
import no.nav.budstikka.domain.dispatch.*
import no.nav.budstikka.domain.dispatch.dsl.*
import org.apache.kafka.clients.producer.ProducerRecord

val encoded = brukervarselCreate(
    reference = "min-sak-123",
    personIdentifier = PersonIdentifier("12345678901"),
    varseltype = Varseltype.BESKJED,
    text = "Du har fått et nytt dialogmøte",
) {
    link = "https://nav.no/dialogmote/123"
    eksternVarsling {
        kanaler(EksternKanal.SMS)
        smsTekst = "Du har et nytt varsel på Min side"
    }
    brevFallback(journalpostId = "jp-1") // sendes som brev hvis mottakeren er reservert
}

val record = ProducerRecord("team-esyfo.budstikka.v1", encoded.partitionKey, encoded.value)
    .apply { headers().add(encoded.headerName, encoded.eventId.toByteArray()) }
producer.send(record)
```

- **Påkrevde felt** (`reference`, `personIdentifier`, `varseltype`, `text`) er funksjonsparametre —
  kompilatoren stopper deg hvis noe mangler.
- **Valgfrie felt** settes i lambdaen.
- `encoded.eventId` genereres av biblioteket (dedup/korrelasjon). Logg den gjerne i din egen app —
  den korrelerer sporingen på tvers av budstikka og deg.

### Flere eksempler

```kotlin
// Ledervarsel — budstikka resolver nærmeste leder selv fra (sykmeldt, orgnummer)
ledervarselCreate(
    reference = "sak-1",
    sykmeldt = PersonIdentifier("12345678901"),
    orgnummer = Orgnummer("987654321"),
    text = "En av dine ansatte har fått et dialogmøte",
) { link = "https://nav.no/dine-sykmeldte" }

// Arbeidsgivervarsel — sealed mottakervalg (nærmeste leder ELLER Altinn-rolle)
arbeidsgivervarselCreate(
    reference = "sak-1",
    orgnummer = Orgnummer("987654321"),
    recipient = narmesteLeder(PersonIdentifier("12345678901")),
    merkelapp = Merkelapp.DIALOGMOETE,
    text = "Innkalling til dialogmøte",
    link = "https://nav.no/ag/dialogmote",
) {
    meldingstype = ArbeidsgiverMeldingstype.OPPGAVE
    sakstilknytning(sakId = "dialogmote-42")
}

// Lukke et tidligere varsel (matcher på reference + mottakeranker)
brukervarselInactivate(reference = "min-sak-123", sykmeldt = PersonIdentifier("12345678901"))
```

Alle variantene har en tilsvarende DSL-funksjon: `dittSykefravaerCreate`, `brevCreate`,
`microfrontendEnable`/`microfrontendDisable`, og `*Inactivate` for de lukkbare kanalene.

## Design

Se `docs/kontrakt.md`, `docs/adr/0010-*` (distribusjon) og `docs/adr/0011-*` (API/DSL) i repoet for
begrunnelsen bak kontraktformen.
