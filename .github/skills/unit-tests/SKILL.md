---
name: unit-tests
description: "Bruk når raske unit-/komponenttester i Kotest skal skrives eller endres: FunSpec, domenelogikk, application-services, fakes/builders, målrettet ./gradlew test --tests, eller når noen sier /unit-tests. Ikke for full boot/Testcontainers — bruk /e2e-tests."
---

# Unit-tester

## Kontrakt

Unit- og komponenttester er raske, lokale og atferdsnære:

- Test gjennom offentlig grensesnitt, ikke private funksjoner eller intern rekkefølge.
- Unngå full app-boot, Kafka og Postgres med Testcontainers her; det hører til `/e2e-tests`.
- Testkode følger `/kotlin`; denne skillen eier testgrense, testform og verifisering.
- Bruk Kotest `FunSpec`.
- Bruk fakes, builders og små testdata rundt systemgrenser.
- Hold setup liten nok til at testen leses direkte.
- Når en unit-test trenger mye context, splitt heller atferden i flere testklasser.

## Arbeidsflyt

1. Velg atferden testen skal bevise. Ferdig når testnavnet beskriver observerbar atferd.
2. Velg testgrense: domain, application-service eller adapter med fake ytterst. Ferdig når testen er rask og ikke binder seg til implementasjonsdetaljer.
3. Skriv testen som `FunSpec`. Ferdig når setup er kort, eller atferden er splittet til en egen testklasse.
4. Kjør målrettet test: `./gradlew test --tests "no.nav.budstikka.<TestKlasse>"`. Ferdig når kommandoen har exit 0.
