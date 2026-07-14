---
name: kotlin
description: "Bruk når Kotlin-kode i syfo-budstikka skal skrives eller refaktoreres: idiomatisk Kotlin, value classes, sealed typer, kotlin.time/kotlinx.datetime, coroutines/async, nullability, immutable data, composition, internal/private scoping, extension functions, navnekonvensjon, SOLID/DRY, hexagonal arkitektur, eller når noen sier /kotlin. Ikke for Ktor-oppsett — bruk /kotlin-ktor."
---

# Kotlin

## Kontrakt

Koden er idiomatisk Kotlin. Rekkefølgen er prioritet:

- Hold koden DRY og SOLID uten å lage grunne abstraksjoner.
- Følg hexagonal arkitektur: domain/application peker innover mot egne modeller og porter; infrastructure adaptere ligger ytterst.
- Foretrekk composition over inheritance; arv brukes bare når domenemodellen eller rammeverket faktisk krever det.
- Bruk coroutines fremfor manuelle tråder; uavhengige suspend-kall kjøres strukturert med `coroutineScope { ... async { ... }.awaitAll() }`, ikke `GlobalScope` eller løsrevet async.
- Hold synlighet smal: `private` for fil-/klasseinternt, `internal` for modulinternt, `public` bare når koden er et bevisst grensesnitt.
- Velg Kotlin-idiomer fremfor Java-style patterns: `kotlin.time`/`kotlinx.datetime`, nullable-typer, collections og coroutines internt; Java-API-er hører bare hjemme ved interop-grenser og oversettes ved adapteren.
- Foretrekk immutable data, nullable-typer med eksplisitt håndtering, expression bodies og extension functions.
- Bruk value classes for små domenetyper når de gjør koden mer typesikker og lesbar, for eksempel identifikatorer som ellers blir rå `String`.
- For sensitive value classes: pakk rå verdier og masker `toString()` slik at logging ikke lekker persondata.
- Modell lukkede utfall og tilstander med `sealed interface`/`data object`/`data class`.
- Bruk ekshaustiv `when` uten `else` over sealed typer når nye varianter skal tvinge eksplisitte valg ved kompilering.
- Bruk `fun interface` for små porter med én operasjon.
- Bruk scope functions når de gjør flyten tydeligere, ikke for å spare linjer.

## Arbeidsflyt

1. Finn laget koden hører hjemme i: domain, application-port/use case eller infrastructure-adapter. Ferdig når avhengighetsretningen er eksplisitt.
2. Skriv minste idiomatiske Kotlin-form som uttrykker atferden. Ferdig når primitive domenetyper, mutable state, Java-style patterns og ekstra abstraksjoner er fjernet eller begrunnet.
3. Sjekk interop. Ferdig når Java-typer bare finnes ved nødvendige grenser og er oversatt til Kotlin-typer ved adapteren.
4. Sjekk concurrency. Ferdig når parallelt arbeid er strukturert, cancellable og bundet til kallende coroutine-scope.
5. Sjekk API-flate. Ferdig når synlighet er smal og inheritance ikke brukes der composition gir enklere kode.
6. Sjekk navngiving. Ferdig når domeneord følger repoets norske domeneord, og mekanikk/teknikk er på engelsk.
7. Kjør smaleste relevante Gradle-gate. Ferdig når kommandoen har exit 0.
