---
name: integration-tests
description: "Bruk når integrasjonstester i Kotest skal skrives eller endres: repository-/adapter-test, application-service med ekte port-adapter, PostgresTestFixture, Flyway/Exposed, TransactionRunner, målrettet ./gradlew test --tests, eller når noen sier /integration-tests. Ikke for full app-boot — bruk /e2e-tests; ikke for ren logikk — bruk /unit-tests."
---

# Integrasjonstester

## Kontrakt

Integrasjonstester beviser én søm mot ekte adapter eller infrastruktur, uten full app-boot:

- Test observerbar atferd gjennom repository, adapter eller application-service, ikke intern SQL-/implementasjonsrekkefølge.
- Bruk ekte grense bare der en fake skjuler kontrakten, for eksempel Postgres med `PostgresTestFixture`, Flyway og Exposed.
- Ikke boot hele appen; full HTTP/Kafka/Postgres-flyt hører til `/e2e-tests`.
- Testkode følger `/kotlin`; denne skillen eier integrasjonsgrense, fixture-livssyklus og Gradle-gate.
- Bruk Kotest `FunSpec`.
- Hold fixture-state deterministisk: migrer før spec, reset mellom tester og close etter spec.
- Kjør med `./gradlew test`; ikke merk som `E2E` med mindre testen booter hele appen.

## Arbeidsflyt

1. Velg sømmen testen skal bevise. Ferdig når offentlig grensesnitt og forventet effekt er eksplisitt.
2. Velg minste ekte grense. Ferdig når fakes brukes for alt som ikke må være ekte for kontrakten.
3. Sett opp fixture-livssyklus. Ferdig når migrering, reset og close er deterministisk plassert.
4. Skriv testen som `FunSpec`. Ferdig når testen følger `/kotlin` og ikke binder seg til intern implementasjon.
5. Kjør målrettet test: `./gradlew test --tests "no.nav.budstikka.<TestKlasse>"`. Ferdig når kommandoen har exit 0.

