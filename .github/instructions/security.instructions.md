---
description: "Bruk når kode i dette Ktor-backendet (no.nav.syfo) berører sikkerhetsgrenser — hemmeligheter/secrets, PII (fødselsnummer, navn, tokens), SQL-bygging, accessPolicy/nettverkstilgang i NAIS, JWT/token-håndtering eller logging av sensitive data. Trigger: 'er dette trygt', 'logg dette', 'bygg denne spørringen', ny outbound-kall eller inbound-rule, hardkodet nøkkel/passord, fnr i kode."
applyTo: "**"
---

# Sikkerhet — Ktor-backend (no.nav.syfo)

Referanse: [sikkerhet.nav.no](https://sikkerhet.nav.no)

Dette er et Kotlin/Ktor-backend på NAIS (Netty, Postgres/Flyway, Kafka, TokenX/Azure AD). Sikkerhet er en hard grense — brudd verifiseres i `.grill/VERIFICATION.md` før jobben regnes som ferdig, og høyrisiko-endringer (auth, migrasjoner, secrets) bør gjennom `grill-inspektor`-review.

## NAIS-plattformen håndterer hemmeligheter

- TokenX- og Azure AD-credentials injiseres av NAIS som miljøvariabler — les dem via `System.getenv(...)`, aldri hardkod.
- Aldri commit hemmeligheter (client secrets, JDBC-passord, API-nøkler). Postgres-credentials kommer fra NAIS-injiserte env-vars (`NAIS_DATABASE_*`), Kafka fra `KAFKA_*`.
- Dependabot + Trivy for sårbarhetsskanning; Chainguard/Distroless som base image (se `docker.instructions.md`).
- Auth-mekanikk og token-validering: se `/auth-overview`.

## Nettverkspolicyer — default-deny

All trafikk inn og ut må deklareres eksplisitt i NAIS-manifestet. Endrer du et endepunkt eller legger til et nedstrøms-kall, oppdater `accessPolicy` i samme PR (se `/nais-manifest`):

```yaml
accessPolicy:
  inbound:
    rules:
      - application: kallende-app
        namespace: team-esyfo
  outbound:
    rules:
      - application: target-app
        namespace: team-target
    external:
      - host: api.ekstern-tjeneste.no
```

## Sikkerhetsgrenser i Kotlin/Ktor

### Parameteriserte spørringer — aldri string-interpolasjon i SQL
Bruk `PreparedStatement`-parametere (eller named params i biblioteket repoet bruker). Aldri bygg SQL med `"... WHERE fnr = '$fnr'"`.

```kotlin
// Riktig
connection.prepareStatement("SELECT * FROM melding WHERE fnr = ?").use { stmt ->
    stmt.setString(1, fnr)
    stmt.executeQuery()
}
```

### Valider input ved systemgrenser
Valider og parse innkommende data i route-handleren / ved deserialisering — ikke stol på at nedstrøms-kode rydder opp. Returner `400 Bad Request` på ugyldig input, ikke ufanget exception.

### Aldri logg PII
Fødselsnummer, tokens/bearer-headere, personnavn og andre sensitive felt skal aldri til logg (verken `logback`, MDC eller exception-meldinger). Logg syntetiske/maskerte verdier eller tekniske ID-er i stedet.

```kotlin
// Feil:  log.info("Hentet melding for $fnr")
// Riktig: log.info("Hentet melding for bruker {}", maskertId)
```

Sensitive logglinjer som likevel trengs for feilsøking → bruk `team-logs`-markering / secure logs per NAV-konvensjon, aldri vanlig stdout.

## Sjekkliste før du regner sikkerhetsarbeid som ferdig

- Ingen hemmeligheter i kode, config eller logg.
- Ingen PII i logg eller exception-meldinger.
- SQL er parameterisert.
- `accessPolicy` i NAIS-manifestet dekker alle nye inn-/ut-kall.
- Token-audience/issuer valideres for beskyttede endepunkter (`/auth-overview`).
