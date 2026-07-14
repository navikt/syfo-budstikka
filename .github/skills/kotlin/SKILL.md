---
name: kotlin
description: "Bruk når Kotlin-kode i syfo-budstikka skal skrives eller refaktoreres: idiomatisk Kotlin, kotlin.time/kotlinx.datetime, coroutines, nullability, immutable data, extension functions, navnekonvensjon, SOLID/DRY, hexagonal arkitektur, eller når noen sier /kotlin. Ikke for Ktor-oppsett — bruk /kotlin-ktor."
---

# Kotlin

## Kontrakt

Koden er idiomatisk Kotlin:

- Bruk `kotlin.time` og `kotlinx.datetime`; Java-API-er hører bare hjemme ved interop-grenser, for eksempel Kafka.
- Oversett Java-typer til Kotlin-typer så nær adapter-grensen som mulig.
- Foretrekk immutable data, nullable-typer med eksplisitt håndtering, expression bodies og extension functions.
- Bruk scope functions når de gjør flyten tydeligere, ikke for å spare linjer.
- Bruk coroutines fremfor manuelle tråder i ny kode.
- Velg Kotlin-idiomer fremfor Java-style patterns når Kotlin har et tydeligere alternativ.
- Hold koden DRY og SOLID uten å lage grunne abstraksjoner.
- Følg hexagonal arkitektur: domain/application peker innover mot egne modeller og porter; infrastructure adaptere ligger ytterst.

Java-style smells å rydde bort: `Optional`, `Stream`, manuelle getters/builders, `Thread`/`CompletableFuture`, mutable samlinger som default og `java.time` uten interop-grunn.

## Arbeidsflyt

1. Finn laget koden hører hjemme i: domain, application-port/use case eller infrastructure-adapter. Ferdig når avhengighetsretningen er eksplisitt.
2. Skriv minste idiomatiske Kotlin-form som uttrykker atferden. Ferdig når mutable state, Java-style patterns og ekstra abstraksjoner er fjernet eller begrunnet.
3. Sjekk tid, dato og interop. Ferdig når `kotlin.time`/`kotlinx.datetime` brukes internt, og Java-typer bare finnes ved nødvendige grenser.
4. Sjekk navngiving. Ferdig når domeneord følger repoets norske domeneord, og mekanikk/teknikk er på engelsk.
5. Kjør smaleste relevante Gradle-gate. Ferdig når kommandoen har exit 0.
