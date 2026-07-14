---
name: e2e-tests
description: "Bruk når full-boot e2e-tester med Kotest og Testcontainers skal skrives, feilsøkes eller kjøres: FunSpec, ekte app, Kafka/Postgres/HTTP, @Tags(\"E2E\"), ./gradlew e2eTest, TestContext, eller når noen sier /e2e-tests."
---

# E2E-tester

## Kontrakt

E2E-tester beviser at ekte app, config og infrastruktur-adaptere henger sammen:

- Bruk Kotest `FunSpec`.
- Merk full-boot-specer med `@Tags("E2E")`.
- Kjør dem med `./gradlew e2eTest`; default `./gradlew test` ekskluderer `E2E`.
- Bruk Testcontainers for eksterne grenser som Kafka og Postgres.
- Bruk `TestContext` når flere klienter, containere eller helper-funksjoner ellers gjør testen tung å lese.
- Test brukerflyt og observerbare effekter, ikke intern implementasjon.

## Arbeidsflyt

1. Velg én full flyt testen skal bevise. Ferdig når startpunkt, ekstern grense og forventet effekt er eksplisitt.
2. Boot appen med repoets teststøtte og nødvendige Testcontainers. Ferdig når testen går gjennom ekte HTTP/Kafka/Postgres der flyten krever det.
3. Skriv specen som `FunSpec` med `@Tags("E2E")`. Ferdig når setup er lesbar, eventuelt via `TestContext`.
4. Kjør `./gradlew e2eTest`. Ferdig når kommandoen har exit 0.

