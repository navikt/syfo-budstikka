# Teststrategi og lokal kjøring — syfo-budstikka

Hvordan vi tester budstikka ende-til-ende og (senere) kjører hele flyten lokalt.
Beslutninger: B50–B53 i `CONTEXT.md`. Bygger på ports & adapters (B28) og teknologivalg (B44).

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
  Application.module(deps)          ← wiring tar avhengigheter som parametre (Ktor-DI, B44)
  Main.kt                           ← prod-entrypoint: wirer EKTE adaptere

src/test/…                         ← IKKE i prod-jaren
  fakes/  FakeDodsfall, FakeReservasjon, FakeNarmesteLeder, FakeKanal …   ← delte fakes
  scenario-byggere (digital-bruker, reservert-brev-fallback, mottaker-dod, nl-mangler, …)
  Testcontainers-base (delt Kotest-extension)
  <e2e>Spec.kt                      ← Kotest e2e mot Testcontainers + fakes
  LokalApp.kt (main)                ← UTSATT: interaktivt lokalt løp
```

**Avvist anti-mønster:** en `if (System.getenv("USE_FAKES"))`-adapterbytte inne i `src/main`.
Da ligger fakene i prod-jaren, og én feilkonfig i prod flipper dem. Grensen skal være i
bygget, ikke i en env-var.

## Infra: Testcontainers-fra-kode (B51)

Kafka + Postgres startes programmatisk via Testcontainers — **samme oppsett** som
integrasjonstestene bruker. Ingen `docker-compose` (unngår en separat fil som drifter fra
test-konfigen).

- DB-tabellene (`inbox`/`leveranse`) er **fullt inspiserbare mens prosessen kjører**:
  containeren mapper Postgres-porten til `localhost`. Logg JDBC-URL ved oppstart (evt. pinn
  en fast host-port) og koble psql/DataGrip/pgweb til.
- Ferskt miljø per kjøring; data overlever ikke en prosess-restart. Live-inspeksjon under
  kjøring får du uansett.
- `withReuse(true)` (infra overlever restart uten compose) legges til hvis/når behovet melder seg.

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
    private val dode = mutableSetOf<Personident>()
    fun marker(ident: Personident) { dode += ident }   // «gjør denne personen død»
    override suspend fun erDod(ident: Personident) = ident in dode
}
```

Fordeler: ingen nettverk, ingen tokens, raske, full kontroll, og de dobler som testdoubler i
enhets-/integrasjonstestene. Portgrensesnittene (B28) er sømmen som gjør byttet mulig.

**WireMock/mockserver** reserveres for utvalgte klient-kontrakttester der vi bevisst vil
verifisere en ekte HTTP-klients kontrakt/serialisering — ikke for det brede e2e/lokale løpet.
Ktor MockEngine er ikke valgt (fake på for lavt abstraksjonsnivå for en domeneblind ruter).

## Testnivåer og scope (B53)

1. **Enhetstester** (`domain`, functional core B28): rene, raske, parallelle — `decide()`,
   mapping, tilstandsoverganger. Ingen containere. (B44/TEKNOLOGI.)
2. **Integrasjons-/e2e-tester (NÅ):** Kotest FunSpec som booter hele appen (konsument +
   workers + Ktor) in-process mot Testcontainers (B51) med port-fakes (B52) wiret inn, og
   asserter at fake-kanalene mottok forventet leveranse. Dekker inbox → `decide()` → outbox →
   levering ende-til-ende via de delte scenario-byggerne (B50). Async workers verifiseres med
   Kotest `eventually { }` til fake-kanal/DB-rad når forventet tilstand.

### Utsatt: interaktivt lokalt HTTP-løp

Et interaktivt løp der du starter `main()` én gang og fyrer scenarier over HTTP
(`POST /dev/formidling`, fake-toggle-endepunkter, navngitte scenarier) + live-inspeksjon via
kafka-ui/pgweb er **utsatt** til behovet melder seg. Det bygges da som et **tynt lag oppå
samme substrat** — samme fakes og scenario-byggere, ingen ny build-kompleksitet, aldri i
`src/main`.

## Kjøring

- Alle tester: `./gradlew test` (kjør før ferdigmelding).
- Enhetstester kjøres parallelt; integrasjonstester bruker Testcontainers (konfigureres i Kotest).
