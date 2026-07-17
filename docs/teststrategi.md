# Teststrategi og lokal kjøring — syfo-budstikka

Hvordan vi tester budstikka ende-til-ende og (senere) kjører hele flyten lokalt.
Beslutninger: B50–B56 i `context.md`. Bygger på ports & adapters (B28) og teknologivalg (B44).

## Grunnidé: ett delt substrat (B50)

Budstikka er domeneblind og bygget som ports & adapters — alle eksterne kall (PDL død,
KRR-reservasjon, nærmeste leder, de seks kanalene) ligger bak grensesnitt («porter») i
`infrastructure`. Et lokalt testløp handler derfor **ikke** om et eget build-mål, men om å
**bytte ekte adaptere mot fakes**.

Behovet for slike fakes — og for å starte Kafka + Postgres — er identisk for de automatiske
integrasjonstestene og for et framtidig interaktivt lokalt løp. Derfor bygger vi det **én
gang** og deler. **Standard: alt i `src/test`** — fakes, scenario-byggere, Testcontainers-base,
e2e-specs og (senere) en kjørbar lokal `main()`. Innenfor ett modul er `src/test` allerede delt
på tvers av alle testklasser, så det trengs verken en ekstra plugin eller et eget source-set.

- **Fakes** (in-memory implementasjoner av portene), **scenario-byggere**, **Testcontainers-base**,
  **e2e-specs** og (senere) **kjørbar `main()`** → `src/test`.
- **Aldri i `src/main`.**

Et eget `testFixtures`-source-set (`java-test-fixtures`-pluginen — merk: Gradle-navn, koden er
Kotlin) innføres **kun hvis** et konkret delingsbehov dukker opp: prosjektet splittes i flere
moduler, eller en ekstern konsument skal gjenbruke fakene. Så lenge det er ett modul og fakene
kun konsumeres av egne tester, er `src/test` enklere og gir alle de samme prod-garantiene.
(Bonus i `src/test`: fakene ser `internal`-medlemmer i `src/main`; `testFixtures` ser bare `public`.)

### Prod-grensen er garantien (B50)

Prod-artefakten (fat-jar / Docker-image) bygges **kun** fra `src/main`. Alt i `src/test`
er fysisk fraværende fra prod-jaren. Det gjør det **umulig** å wire en fake i prod
— håndhevet av Gradle/kompilatoren, ikke av menneskelig disiplin.

```
src/main/…                         ← PROD (kun dette shippes)
  Application.kt                    ← module() (prod) + configureApplication(overrides) (wiring-søm)
  application.conf                  ← prod-entrypoint: refererer ApplicationKt.module (EKTE adaptere)

src/test/…                         ← IKKE i prod-jaren
  fakes/  FakeDeathLookup …                                      ← delte port-fakes
  testsupport/  BudstikkaTestApp (harness), KafkaTestContainer   ← delt substrat
  e2e/  DispatchToInboxE2ESpec.kt   ← @Tags("E2E"), booter hele appen mot Testcontainers + fakes
  LocalApp.kt (main)                ← kjørbart lokalt løp: ./gradlew runLocal
```

**Avvist anti-mønster:** en `if (System.getenv("USE_FAKES"))`-adapterbytte inne i `src/main`.
Da ligger fakene i prod-jaren, og én feilkonfig i prod flipper dem. Grensen skal være i
bygget, ikke i en env-var.

## Infra: Testcontainers-fra-kode (B51)

Kafka + Postgres startes programmatisk via Testcontainers — **samme oppsett** som
integrasjonstestene bruker. Ingen `docker-compose` (unngår en separat fil som drifter fra
test-konfigen).

- DB-tabellene (`inbox`/`delivery`) er **fullt inspiserbare mens prosessen kjører**:
  containeren mapper Postgres-porten til `localhost`. Logg JDBC-URL ved oppstart (evt. pinn
  en fast host-port) og koble psql/DataGrip/pgweb til.
- Ferskt miljø per kjøring; data overlever ikke en prosess-restart. Live-inspeksjon under
  kjøring får du uansett.
- `withReuse(true)` (infra overlever restart uten compose) legges til hvis/når behovet melder seg.

### Delt Postgres-container + schema-isolasjon per fixture (B60)

For fart deler alle DB-testene i én JVM **én** Postgres-container (`PostgresTestFixture`
lazy-starter en delt container én gang, i stedet for én ny container per spec — det var den
dominerende kaldkjørings-kostnaden). Isolasjon beholdes ved at hver `PostgresTestFixture`-instans
lever i sitt **eget schema** (`test_<uuid>`, opprettet ved init, droppet i `close()`); `migrate()`
kjører Flyway inn i det schemaet og `database`-tilkoblingen setter `currentSchema` dit. Samtidige
specer (Kotest `Concurrent`, jf. `BudstikkaTestConfiguration`) tråkker derfor ikke på hverandres
rader selv om `reset()` fortsatt `TRUNCATE`-er. Full-boot-substratet (`BudstikkaTestApp`/`runLocal`)
peker den bootede appens `database.url` mot det samme fixture-schemaet (`?currentSchema=…`), så
boot-migrering, konsument og assertions ser samme schema. Containeren stoppes ikke lenger i
`close()` — Testcontainers Ryuk river den ved JVM-slutt.


### Ingen Texas, ingen tokens, ingen compose lokalt

Fordi fakene (B52) erstatter alt autentisert nedstrøms, gjøres det ingen ekte HTTP-kall
lokalt. Da trengs **verken Texas/token-sidecar, ekte tokens, TokenX-validering eller
docker-compose** — token-laget lever i de ekte adapterne i `src/main`, som ikke er på det
lokale classpath-et. Lokalt oppsett = kun Kafka + Postgres (Testcontainers) + in-process fakes.

## Fakes: in-process port-fakes (B52)

Standard er **in-process port-fakes** — Kotlin-implementasjoner av portgrensesnittene, i
minne, styrbare:

```kotlin
class FakeDodsfall : DodsfallOppslag {
    private val dode = mutableSetOf<PersonIdentifier>()
    fun marker(ident: PersonIdentifier) { dode += ident }   // «gjør denne personen død»
    override suspend fun erDod(ident: PersonIdentifier) = ident in dode
}
```

Fordeler: ingen nettverk, ingen tokens, raske, full kontroll, og de dobler som testdoubler i
enhets-/integrasjonstestene. Portgrensesnittene (B28) er sømmen som gjør byttet mulig.

**WireMock/mockserver** reserveres for utvalgte klient-kontrakttester der vi bevisst vil
verifisere en ekte HTTP-klients kontrakt/serialisering — ikke for det brede e2e/lokale løpet.
Ktor MockEngine er ikke valgt (fake på for lavt abstraksjonsnivå for en domeneblind ruter).

## Testnivåer og scope (B53)

1. **Enhetstester** (`domain`, functional core B28/B55): rene, raske, parallelle — beslutningsgater
   (`DeathGate`, `DecisionProcess`), mapping, tilstandsoverganger. Ingen containere. (B44/TEKNOLOGI.)
2. **Integrasjons-/e2e-tester (NÅ):** Kotest FunSpec som booter hele appen (konsument +
   workers + Ktor) in-process mot Testcontainers (B51) med port-fakes (B52) wiret inn, og
   asserter at fake-kanalene mottok forventet leveranse. Dekker inbox → beslutning → outbox →
   levering ende-til-ende via de delte scenario-byggerne (B50). Async workers verifiseres med
   Kotest `eventually { }` til fake-kanal/DB-rad når forventet tilstand. **Opt-in (B56):** de
   fulle e2e-specene er merket `@Tags("E2E")` og kjøres KUN via `./gradlew e2eTest` — de er
   ekskludert fra default `test`/`check`, så CI/CD ikke venter på treg container-boot ved hver
   deploy. Schema-/`PostgresTestFixture`-testene er bevisst UTEN E2E-tag og kjører i default `test`.

### Wiring-sømmen (B44/B56)

Prod og test deler samme boot. `Application.kt` har en null-arg `module()` (referert fra
`application.conf`, wirer EKTE adaptere) som delegerer til `configureApplication(overrides)`.
Testene/`LocalApp` booter appen programmatisk og sender inn `overrides` som `provide`-er fakes
SIST. Sømmen er `ktor.di.conflictPolicy = "OverridePrevious"` — satt KUN i test-konfigen, så en
senere `provide` vinner over den ekte. I prod står default-policyen, så et duplikat-`provide`
kaster (sikkerhet mot utilsiktet override). Fakene finnes aldri i prod-jaren (build-grensen, B50).

### Levert: kjørbart lokalt løp — utsatt: HTTP-kontrollplan

`./gradlew runLocal` booter nå HELE appen mot Testcontainers (Postgres + Kafka) med port-fakes
wiret inn (`LocalApp.kt`, samme substrat `BudstikkaTestApp` som e2e-specene bruker). Prosessen
står og kjører til Ctrl+C; JDBC-URL, Kafka-bootstrap, formidling-topic og Kafka UI-URL logges ved
oppstart for live-inspeksjon. Fakene byttes via samme wiring-søm som testene (`overrides` → `provide`).

I det lokale løpet — og KUN der — startes også en **Kafka UI** (`provectuslabs/kafka-ui`) i en egen
Testcontainer, så topics, meldinger, konsumentgrupper og offsets kan inspiseres i nettleseren mens
appen kjører. Kafka og Kafka UI legges på et delt Docker-nett (Kafka får alias `kafka` + intern
lytter `kafka:19092`); UI-web-porten mappes ut så den er nåbar fra host. E2e-specene lar dette stå
av (`enableKafkaNetwork = false`, ingen UI-container), så gaten holder seg rask og uten UI-overhead.
Kafka UI legger ~30–60 s på oppstarten av `runLocal` (Spring Boot + health-venting) — greit for et
lokalt verktøy.

Fortsatt **utsatt** til behovet melder seg: et interaktivt HTTP-kontrollplan oppå det samme
løpet (`POST /dev/formidling`, fake-toggle-endepunkter, navngitte scenarier) + live-inspeksjon
via pgweb. Bygges da som et **tynt lag** — samme fakes og substrat, aldri i `src/main`.

## Kjøring

- **Default:** `./gradlew test` (kjør før ferdigmelding). Enhets- + integrasjons-/schema-tester;
  de fulle e2e-specene (`@Tags("E2E")`) er ekskludert her, så gaten er rask. `./gradlew check`
  inkluderer heller ikke e2e.
- **Opt-in e2e:** `./gradlew e2eTest` — kjører KUN de E2E-taggede full-boot-specene mot
  Testcontainers (B56). Bruk lokalt / i en egen manuell eller nattlig CI-jobb, ikke i deploy-løpet.
- **Lokalt løp:** `./gradlew runLocal` — booter hele appen mot Testcontainers med fakes; Ctrl+C
  for å stoppe (river ned containerne via shutdown-hook).
- Enhetstester kjøres parallelt; integrasjons-/e2e-tester bruker Testcontainers (Docker må kjøre).
