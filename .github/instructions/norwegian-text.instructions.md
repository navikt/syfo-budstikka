---
description: "Bruk når du skriver eller endrer norsk markdown i dette repoet: README, docs/ (ADR/glossar/kontekst), .grill/-artefakter (PLAN.md, VERIFICATION.md), PR-beskrivelser, commit-meldinger og description-felt i instruksjonsfiler."
applyTo: "**/*.md"
---

# Norsk tekstkvalitet (lean)

Disse reglene gjelder all norsk markdown-tekst i dette Ktor-backend-repoet (no.nav.syfo): README, arkitekturbeslutninger i `docs/adr/`, `.grill/`-artefakter, PR-tekst og commit-meldinger. Hold tekst om kode like presis som koden selv.

## AI-markører å unngå

- Overforbruk av em-dash (`—`) i løpende tekst og kulepunkter
- Formler som "ikke bare X, men også Y"
- Klisjeer som "i et stadig skiftende landskap"
- Overdrevne adjektiver: "banebrytende", "revolusjonerende", "sømløs", "robust"
- Oppsummeringspåheng som "kort sagt" når setningen ikke tilfører ny info

Skriv heller konkret hva som skjer, hvem som gjør det, og hva leseren skal gjøre.

## Det viktigste først

Start med konklusjon eller beslutning. Bakgrunn og begrunnelse kommer etterpå.
Målet er forventningsstyring: leseren skal forstå utfallet tidlig, ikke bygges opp mot det.

I en ADR: skriv beslutningen i første setning, så konteksten og konsekvensene.
I en PR-beskrivelse: skriv hva endringen gjør for konsumenten (endepunkt, Kafka-topic, migrering) før implementasjonsdetaljer.

## Unngå substantivsyke

```text
❌ Vi foretar en vurdering av implementasjonen.
✅ Vi vurderer implementasjonen.

❌ Det er gjort en oppdatering av Flyway-migreringen.
✅ Vi oppdaterte Flyway-migreringen.
```

Foretrekk verb og aktiv form. Kutt unødvendige hjelpeord.

## Anglisismer og Nav-språkpraksis

Bruk norsk når det finnes naturlige ord, men behold etablerte fagtermer.
Vanlige feller:

- "adressere et problem" → "løse" / "ta tak i"
- "ta eierskap til" → "ha ansvar for"
- "har du noen input?" → "har du innspill?"
- "shippe" / "deploye" → "levere" / "rulle ut"
- "på slutten av dagen" → dropp eller skriv poenget direkte

Behold tekniske termer som er etablerte i stacken: Ktor, Netty, NAIS, TokenX, Azure AD, Flyway, Kafka, consumer, producer, endepunkt.
I sammensatte ord med engelsk fagterm: bruk bindestrek (`CI-pipeline`, `API-kall`, `Kafka-consumer`, `Flyway-migrering`).

## «Nav», ikke «NAV»

Organisasjonen skrives **Nav** (ikke «NAV») i løpende tekst — det er gjeldende navnepraksis. «Nav-team», «en Nav-tjeneste», «Nav-utvikler».
Unntak som beholder store bokstaver (egennavn / tekniske identifikatorer): `NAIS`, JWT-claimet `NAVident`, pakkenavn (`no.nav.syfo`), URL-er og kode-identifikatorer. Disse endres aldri.

## Tredjeperson i description-felt

Når du skriver `description` i frontmatter for instruksjonsfiler eller skills, bruk tredjeperson og beskriv NÅR filen gjelder. Skriv hva filen gjør, ikke hva "jeg" eller "du" gjør.

```text
✅ "Gjelder når du endrer Ktor-ruter ..."
❌ "Jeg hjelper deg med ..."
```
