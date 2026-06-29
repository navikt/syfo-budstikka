# Når du mocker

Mock kun ved **systemgrenser**:

- Eksterne HTTP-tjenester (andre fagsystemer, TokenX/Azure AD token-endepunkter, integrasjoner)
- Tid og tilfeldighet (`Clock`, UUID-generering)
- Sjelden: filsystem

Ikke mock:

- Dine egne klasser/tjenester (`no.nav.syfo.*`)
- Interne samarbeidspartnere
- Databasen — foretrekk en ekte test-Postgres via Testcontainers med Flyway-migreringer kjørt, så testene fanger SQL- og skjemafeil
- Kafka — foretrekk en ekte broker via Testcontainers (`KafkaContainer`) fremfor å mocke produsent/konsument

Hovedregel: **må du mocke en intern samarbeidspartner, er modulgrensen sannsynligvis feil.**

## Design for testbarhet

Ved systemgrenser, lag grensesnitt som er enkle å bytte ut i test.

**1. Bruk dependency injection — send avhengigheter inn**

Send eksterne avhengigheter inn i stedet for å konstruere dem internt:

```kotlin
// Lett å bytte ut: HttpClient og Clock injiseres
class SoknadService(
    private val pdlClient: PdlClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun registrer(soknad: Soknad): RegistrertSoknad =
        RegistrertSoknad(soknad, mottatt = Instant.now(clock))
}

// Vanskelig å teste: konstruerer klienten og leser miljø selv
class SoknadService {
    private val pdlClient = PdlClient(System.getenv("PDL_URL"))
}
```

**2. Foretrekk spesifikke klientgrensesnitt over én generisk fetcher**

Lag én funksjon per ekstern operasjon i stedet for én generisk funksjon med betinget logikk:

```kotlin
// GOD: hver funksjon er uavhengig mockbar / lett å fake
interface PdlClient {
    suspend fun hentPerson(ident: String): Person
    suspend fun hentNavn(ident: String): Navn
}

// DÅRLIG: mocking krever betinget logikk inne i mocken
interface GenericClient {
    suspend fun call(path: String, body: Any?): JsonNode
}
```

Det spesifikke grensesnittet gir: hver fake returnerer én konkret form, ingen betinget logikk i testoppsettet, og det er lett å se hvilke kall en test faktisk utløser.

**3. Foretrekk en enkel fake fremfor en streng mock for atferdstester**

Når du tester atferd (ikke samhandling), er ofte en liten håndskrevet fake-implementasjon av grensesnittet klarere enn `mockk`-oppsett — den dokumenterer kontrakten og lekker ikke kall-rekkefølge inn i testen.

## Ktor: mock HTTP-grenser, ikke ditt eget

For utgående HTTP, bruk Ktor sin `MockEngine` til å fake fjernsvar i stedet for å mocke din egen klientklasse:

```kotlin
val mockEngine = MockEngine { request ->
    respond(
        content = """{"navn":"Kari"}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
val client = HttpClient(mockEngine) { /* samme config som prod */ }
```

Da tester du din egen serialisering, feilhåndtering og retry-logikk gjennom det ekte klientlaget, men uten å treffe nettverket.
