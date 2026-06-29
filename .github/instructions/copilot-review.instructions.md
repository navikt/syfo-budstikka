---
description: "Aktiveres når GitHub Copilot Code Review kjører på en PR i dette repoet — styrer hvilke funn som prioriteres og hvordan kommentarer formuleres"
applyTo: "**"
---

# Copilot Code Review — retningslinjer for syfo-budstikka

Disse instruksjonene brukes når GitHub Copilot Code Review aktiveres på PR-er i dette
Ktor-backendet (Kotlin, Netty, NAIS, Postgres/Flyway/Kafka, TokenX/Azure AD; pakke `no.nav.syfo`).

Formålet er korte, presise review-forslag med høy signalverdi. Instruksjonen supplerer
repo-spesifikke regler i andre `instructions`-filer, men overstyrer dem ikke.

## Kjernesjekker

1. **Scope-disiplin og diff-disproporsjon**
   - Vurder om endringen holder seg til oppgavens scope.
   - Flagg når diffen rører ubeslektet kode eller virker ute av proporsjon med oppgaven.
   - Typiske tegn: store formatterings-sveip, fil-rename uten funksjonell grunn, refactor utenfor stated scope, urelaterte avhengighets-bump i `build.gradle.kts` eller `gradle/libs.versions.toml`.

2. **Sikkerhet og persondata**
   - Databasekall skal bruke parameteriserte queries (prepared statements), aldri streng-konkatenert SQL.
   - Secrets skal ikke ligge i kode, `application.conf`, NAIS-manifest, testdata eller logger — de hentes fra miljøvariabler/secrets.
   - Personopplysninger (fnr, navn, diagnose, sykmeldingsdata) skal aldri logges eller eksponeres i feilmeldinger/API-svar uten klart behov.
   - Flagg alltid røde signaler: endringer i auth (TokenX/Azure AD-validering, `issuer`/`audience`/`jwks`), NAIS `accessPolicy`, auditlogg, nye eksterne integrasjoner eller `serviceuser`-tilgang.
   - Ved tvil: foreslå manuell sikkerhetsgjennomgang via `/security-review`.

3. **Ktor- og backend-korrekthet**
   - Sjekk at nye routes ligger bak riktig `authenticate { }`-blokk, og at åpne `/internal`-endepunkter (isalive/isready/metrics) ikke krever auth.
   - Sjekk at suspending kall ikke blokkerer event-loopen (ingen blokkerende I/O uten `Dispatchers.IO`).
   - Sjekk at HTTP-statuskoder og feilhåndtering er konsistente (`StatusPages`, ikke svelgede exceptions).
   - Sjekk at ressurser lukkes (DB-connections, HttpClient, Kafka-consumere) og at coroutine-scope ikke lekker.

4. **Datalag, migrasjoner og Kafka**
   - Flyway-migrasjoner skal være additive og idempotent-vennlige; flagg endring av allerede commitet migrasjonsfil (skal være ny `V__`-fil i stedet).
   - Sjekk at nye kolonner/constraints ikke bryter eksisterende data eller rullerende deploy.
   - For Kafka: sjekk at consumer-offsets håndteres riktig, at deserialiseringsfeil ikke stopper konsumet stille, og at topic/konsumentgruppe matcher konvensjon.

## Avgrensning

- Denne filen beskriver kun kjernesjekker for Copilot Code Review.
- Dype arkitektur- og domenereviews håndteres av egne agenter og skills, koblet til `@grillmester`-faseløkka og `.grill/`-artefakter (`CONTEXT.md`, `adr/`, `PLAN.md`, `VERIFICATION.md`).
- Reviewkommentarer skal være handlingsrettede, med tydelig risiko og anbefalt endring.

## Prioritering i kommentarer

1. Korrekthet og sikkerhet (auth, persondata, dataintegritet)
2. Driftsrisiko (migrasjoner, rullerende deploy, NAIS-config, ressurslekkasjer)
3. Vedlikeholdbarhet og scope-disiplin

## Kommentarstil

- Kommentaren skal vise *hva* som er problemet, *hvorfor* det betyr noe, og *hva* som bør endres.
- Bruk korte Kotlin-/Ktor-eksempler der det gjør forslaget konkret.
- Unngå lange avsporinger; hold fokus på observerbar risiko i PR-en.
- Unngå duplikater når samme funn gjelder flere steder; samle i ett tydelig punkt.

Hvis ingen konkrete funn finnes, skal reviewen være kort og bekrefte at endringen ser
konsistent ut med repoets mønstre.
