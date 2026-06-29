# Gode og dårlige tester

## Gode tester

**Integrasjonsstil**: test gjennom ekte grensesnitt, ikke gjennom mocks av interne deler. I dette Ktor-backendet går de fleste gode testene inn via HTTP-laget med `testApplication`, eller kaller domenetjenesten direkte via dens offentlige funksjon.

```kotlin
// GOD: tester observerbar atferd gjennom HTTP-grensesnittet
@Test
fun `root-endepunkt svarer 200`() = testApplication {
    application { configureRouting() }
    assertEquals(HttpStatusCode.OK, client.get("/").status)
}
```

Kjennetegn:

- Tester atferd kallere bryr seg om (statuskoder, responsinnhold, sideeffekter som er synlige via API-et)
- Bruker kun offentlig grensesnitt
- Overlever intern refaktorering
- Beskriver HVA, ikke HVORDAN
- Én logisk assertion per test

Testnavn på norsk i backticks leser som spesifikasjon:

```kotlin
@Test fun `kall uten gyldig token gir 401`() = testApplication { /* ... */ }
@Test fun `søknad med ugyldig fnr avvises med 400`() = testApplication { /* ... */ }
```

## Dårlige tester

**Implementasjonsdetalj-tester**: koblet til intern struktur.

```kotlin
// DÅRLIG: tester at en intern samarbeidspartner ble kalt
@Test
fun `behandleSoknad kaller validator`() {
    val validator = mockk<SoknadValidator>()
    every { validator.valider(any()) } returns true
    behandleSoknad(soknad, validator)
    verify { validator.valider(soknad) }   // tester HVORDAN, ikke HVA
}
```

Røde flagg:

- Mocking av interne samarbeidspartnere
- Testing av private funksjoner
- Assertion på kall-antall eller rekkefølge (`verify(exactly = ...)` på intern kode)
- Test ryker ved refaktorering uten atferdsendring
- Testnavn beskriver HVORDAN, ikke HVA

```kotlin
// DÅRLIG: omgår grensesnittet for å verifisere
@Test
fun `lagreSoknad skriver til databasen`() = runBlocking {
    lagreSoknad(Soknad(fnr = "..."))
    val rad = dataSource.connection.use {
        it.prepareStatement("SELECT * FROM soknad WHERE fnr = ?").run { /* ... */ }
    }
    assertNotNull(rad)
}

// GOD: verifiserer gjennom grensesnittet
@Test
fun `lagret søknad kan hentes igjen`() = runBlocking {
    val lagret = soknadService.lagre(Soknad(fnr = "12345678901"))
    val hentet = soknadService.hent(lagret.id)
    assertEquals("12345678901", hentet.fnr)
}
```

## Tips for dette repoet

- I Ktor 3.x laster `testApplication` ikke moduler fra `application.yaml` automatisk — last modulen eksplisitt i `application { ... }`-blokken, slik `ServerTest` gjør.
- Trenger du Postgres/Flyway eller Kafka i en test, kjør mot ekte infrastruktur via Testcontainers fremfor å mocke databasen — det gir tester som faktisk fanger SQL- og migreringsfeil. Mock heller HTTP-grenser (TokenX/Azure AD, andre fagsystemer). Se [mocking.md](mocking.md).
