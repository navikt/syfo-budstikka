# ADR 0006 — Opt-in gating av fulle e2e-tester (egen Gradle-task + Kotest-tag)

- Status: besluttet (issue #35, lokalt løp + e2e-harness)
- Dato: 2026-07-13
- Relatert: beslutning B50–B53, B56 i `docs/context.md`, `docs/teststrategi.md`, ADR 0004 (workers), teknologivalg B44
- Merk: ADR-nummer 0006 er valgt for å reservere 0005 til den planlagte
  «komponerbare-beslutningsgater»-ADR-en (B55, egen gren) og unngå kollisjon ved merge.

## Kontekst

Issue #35 leverer et delt ende-til-ende-substrat (`BudstikkaTestApp`) som booter hele appen
(Kafka-konsument + workers + Ktor) in-process mot Testcontainers (Postgres + Kafka), med port-fakes
wired inn via et dependency-injection point (`configureApplication(overrides)`). Samme substrat driver både de
automatiske e2e-specene og et kjørbart lokalt løp (`./gradlew runLocal`).

Disse full-boot-testene starter to Docker-containere og venter på asynkron prosessering
(`eventually`). De tar ofte titalls sekunder per kjøring. B53 forutsatte «automatiske e2e-specer»
uten å ta stilling til når de kjører. Kravet fra
#35 er eksplisitt: **CI/CD skal ikke stå og vente på trege e2e-tester ved hver deploy.** Samtidig
skal den lette schema-drift-testen (`PostgresTestFixture` + `MigrationUtils`) fortsatt kjøre i den
vanlige gaten — den er rask og fanger reelle migrasjonsfeil.

Vurderte mekanismer for opt-in:

1. **Egen Gradle-task + Kotest-tag.** `@Tags("E2E")` på full-boot-specene; en egen `e2eTest`-task
   kjører kun taggede specer, og default `test` ekskluderer dem (`kotest.tags=!E2E`).
2. **Env var switch i én test-task.** Én `test`-task som betinget inkluderer e2e ut fra en
   miljøvariabel. Forkastet: skjuler hvilke tester som faktisk kjørte bak runtime-tilstand, og gjør
   `./gradlew test` ikke-deterministisk mellom miljøer.
3. **Eget source-set / egen modul for e2e.** Sterkere isolasjon, men innfører build-kompleksitet
   (nytt source-set, egen classpath-graf) som B50 eksplisitt vil unngå så lenge alt bor i `src/test`.

## Beslutning

Vi bruker **mekanisme 1: Kotest-tag + egen Gradle-task**.

1. **Tag kun full-boot-specene.** De ende-til-ende-specene som booter hele appen får
   `@Tags("E2E")`. Enhetstester og schema-/`PostgresTestFixture`-tester er bevisst UTEN tag.
2. **Default `test` ekskluderer E2E.** `test`-tasken kjører med `kotest.tags=!E2E`, så
   `./gradlew test` og `./gradlew check` (deploy-gaten) er raske og container-lette bortsett fra
   den korte schema-testen. Deploy venter aldri på full-boot-e2e.
3. **`./gradlew e2eTest` kjører kun e2e.** En egen `Test`-task med `kotest.tags=E2E`, egen
   `testClassesDirs`/`classpath` (samme `src/test`-output), `shouldRunAfter("test")`, og bevisst
   ikke wired inn i `check`. Brukes lokalt og i en egen manuell/nattlig CI-jobb.
4. **`./gradlew runLocal` deler samme substrat.** Det lokale løpet (`LocalApp.main`) booter via
   samme `BudstikkaTestApp` — én kilde, ingen drift mellom e2e og lokalt løp (B50).

Dependency-injection point for fake-injeksjon: `ktor.di.conflictPolicy = "OverridePrevious"` settes
kun i test-konfigen, slik at en senere `provide` (fake) vinner over den ekte adapteren. I prod står
default-policyen, så et duplikat-`provide` kaster — en utilsiktet override er umulig i prod. Fakene
finnes uansett aldri i prod-jaren (build-grensen, B50).

## Konsekvenser

- ➕ Deploy-gaten (`test`/`check`) er rask og deterministisk; CI/CD venter ikke på container-boot.
- ➕ Schema-drift-testen kjører fortsatt i default-gaten (rask, høy verdi).
- ➕ Én kilde for e2e og lokalt løp (samme `BudstikkaTestApp`), ingen ny build-kompleksitet — alt i
  `src/test` (B50).
- ➕ Hvilke tester som kjører er statisk og synlig fra task-navnet, ikke skjult bak en env-var.
- ➖ E2E kjører ikke automatisk på hver PR/deploy → en regresjon fanget kun av full-boot-e2e kan
  slippe gjennom til den kjøres. Motvirkes ved å kjøre `e2eTest` i en egen (manuell/nattlig) jobb og
  lokalt før større endringer i boot-wiringen.
- ➖ To test-tasks å holde styr på (`test`, `e2eTest`); nye e2e-specer MÅ merkes `@Tags("E2E")` for
  ikke å havne i default-gaten. Konvensjonen er dokumentert i `docs/teststrategi.md`.
- ➖ Reviderer B53s implisitte forutsetning om at e2e er en del av den vanlige testkjøringen —
  eksplisittert i B56.

## Alternativer vurdert

Se mekanismene 1–3 i Kontekst. Env var switch (2) ble forkastet fordi den gjør default-gaten
ikke-deterministisk og skjuler hva som kjørte. Eget source-set/modul (3) ble forkastet fordi det
innfører build-kompleksitet B50 eksplisitt utsetter til et konkret delingsbehov melder seg; en
Kotest-tag gir samme opt-in uten ny source-set-graf.

Utsatt (dokumentert, ikke besluttet nå): en dedikert CI-workflow som kjører `e2eTest` nattlig eller
ved merge til `main`, og et interaktivt HTTP-kontrollplan oppå det lokale løpet (B53).
