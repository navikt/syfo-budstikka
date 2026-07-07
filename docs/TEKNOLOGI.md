# Teknologivalg — syfo-budstikka

Idiomatisk, moderne Kotlin — **ikke** Spring-aktig. Testbarhet er et førsteklasses krav:
domenelaget er rammeverksfritt og testes rent og raskt. Alle avhengigheter legges i
version catalog (`gradle/libs.versions.toml`, refereres som `libs.*` / `ktorLibs.*`),
aldri hardkodede versjoner.

## Kjøretid og rammeverk

- **Kotlin/JVM** på **Ktor + Netty** (`io.ktor.server.netty.EngineMain`) — allerede i skjelettet.
- **DI: Ktors innebygde dependency injection** (Ktor 3.2+), ikke Koin, ikke Spring.
  Konstruktør-injeksjon, wiring i `Application.*Module()`. Holder `domain` fri for rammeverk.

## Data

- **Postgres 18** (esyfovarsel kjørte 17) — Cloud SQL via NAIS.
- **Exposed DSL** (JetBrains) — typet SQL-DSL, *ikke* DAO/ORM-magi og *ikke* rå JDBC.
  Parameteriserte spørringer ivaretas av DSL-en. Må uttrykke `FOR UPDATE SKIP LOCKED`
  for worker-radlåsen (B15/B27). **HikariCP** som connection pool.
- **Flyway** for skjemaendringer (`src/main/resources/db/migration/V<n>__*.sql`), additivt.
- **UUID v7** (tidssortert) for interne id-er som `leveranse.id` (B16). Gir bedre B-tree-lokalitet
  enn v4 og hjelper alders-baserte retensjons-`DELETE` (B42). **Postgres 18 har `uuidv7()` innebygd**
  → id-en genereres av databasen med `DEFAULT uuidv7()` (settes i Flyway-migreringen), ingen app-side
  generator. **Standard i budstikka: `java.util.UUID` via `javaUUID("id")`** (pakke
  `org.jetbrains.exposed.v1.core.java`) — ingen experimental opt-in, best interop mot Kafka/JDBC/serialisering
  på en ren JVM-backend. (`uuid("id")` i Exposed 1.0 er `kotlin.uuid.Uuid` og krever
  `@OptIn(ExperimentalUuidApi::class)` — velges ikke her.) Marker kolonnen `.databaseGenerated()` så Exposed
  leser id-en tilbake i stedet for å sende en. **Ikke** `.autoGenerate()` — den lager en klient-side v4.
  Merk: `eventId` (B4) settes av produsent-appene
  (innkommende Kafka-kontrakt), ikke av budstikkas DB.

## Kafka

- **Plain Apache Kafka** (`kafka-clients`), konsument/produsent i egen coroutine ved siden av
  Ktor-serveren (jf. `/kafka-topic`). Ikke Rapids & Rivers. SSL-config injiseres av NAIS.

## Prosjektstruktur (DDD / ports & adapters)

Pakker under `no.nav.syfo`:

- **`domain`** — ren kjerne: modellen (`Formidling`, `Beslutning`, tilstander) og `decide()`
  (B28 functional core). Ingen I/O, ingen rammeverk. Raske, parallelliserbare enhetstester.
- **`infrastructure`** — imperative shell / adaptere: Kafka, Exposed-repositories, eksterne
  klienter (KRR, PDL, nærmeste leder, dokdist, notifikasjon-produsent-api), DataSource, config.
- **`api`** — Ktor-routes: interne endepunkter (`/internal/isalive|isready|prometheus`), ev. admin.

Mapping til B28: `domain` = functional core · `infrastructure` = imperative shell · `api` = HTTP-kant.

## Test

- **Kotest** med **FunSpec**-stil som testrammeverk.
- **MockK** for mocking.
- **Testcontainers** via Kotest-extension for Postgres/Kafka i integrasjonstester.
- To nivåer: (1) raske, rene **enhetstester** av `domain` (`decide()`, mapping) — ingen
  containere, kjøres **parallelt**; (2) **integrasjonstester** (repositories, konsument,
  ende-til-ende) med Testcontainers. Parallellitet konfigureres i Kotest.
- **ktlint** (allerede i repoet) for kodestil. Kjør `./gradlew test` før ferdigmelding.
- Lokal test/e2e-strategi (delt substrat, `testFixtures`/prod-grense, port-fakes, utsatt
  interaktivt løp): se `TESTSTRATEGI.md` (B50–B53).
