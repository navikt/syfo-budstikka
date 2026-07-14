---
name: unit-tests
description: "Bruk når raske unit-/komponenttester i Kotest skal skrives eller endres: FunSpec, domenelogikk, application-services, fakes/builders, TestContext, målrettet ./gradlew test --tests, eller når noen sier /unit-tests. Ikke for full boot/Testcontainers — bruk /e2e-tests."
---

# Unit-tester

## Kontrakt

Unit- og komponenttester er raske, lokale og atferdsnære:

- Bruk Kotest `FunSpec`.
- Test gjennom offentlig grensesnitt, ikke private funksjoner eller intern rekkefølge.
- Bruk `TestContext` når det gjør testen mer lesbar; behold vanlig `FunSpec` når det er tydeligere.
- Bruk fakes, builders og små testdata rundt systemgrenser.
- Unngå full app-boot, Kafka og Postgres med Testcontainers her; det hører til `/e2e-tests`.

## Arbeidsflyt

1. Velg atferden testen skal bevise. Ferdig når testnavnet beskriver observerbar atferd.
2. Velg testgrense: domain, application-service eller adapter med fake ytterst. Ferdig når testen ikke binder seg til implementasjonsdetaljer.
3. Skriv testen som `FunSpec`. Ferdig når setup er kort, eller flyttet til `TestContext` fordi lesbarheten øker.
4. Kjør målrettet test: `./gradlew test --tests "no.nav.budstikka.<TestKlasse>"`. Ferdig når kommandoen har exit 0.
