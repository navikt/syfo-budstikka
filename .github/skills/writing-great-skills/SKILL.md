---
name: writing-great-skills
description: "Bruk når du skal skrive en ny Copilot-skill, endre eller forbedre en eksisterende skill under .github/skills/ i dette repoet, eller vurdere om en skill trigger riktig — typisk når noen sier 'lag en skill', 'skriv en copilot-skill', 'forbedre denne skillen', 'hvorfor fyrer ikke skillen', eller /writing-great-skills."
---

# Skrive gode skills (syfo-budstikka)

En skill finnes for å presse **forutsigbarhet** ut av et stokastisk system: at Copilot tar samme _prosess_ hver gang, ikke at den produserer samme tekst. Det er rotdyden — alt under tjener den. Skills i dette repoet ligger i `.github/skills/<navn>/SKILL.md`, er på **norsk**, og er rettet mot en NAV Ktor-backend (`no.nav.syfo`).

Vokabular i **fet skrift** er definert i [references/vokabular-og-feilmodi.md](references/vokabular-og-feilmodi.md) — slå opp der når du trenger full betydning eller skal diagnostisere en skill som ikke oppfører seg.

## Frontmatter — kun `name` + `description`

Husstandarden her er to felter, ikke flere. Ikke innfør egne YAML-felter.

- **`name`** — mappenavnet, kebab-case. Dette blir også `/slash`-navnet.
- **`description`** — sier **NÅR** skillen brukes, aldri hva arbeidsflyten gjør. Dette er det eneste Copilot ser før den fyrer skillen, så den må være en ren samling av **trigger-signaler**: oppgavetyper, fraser brukeren sier, filer/områder som røres, og `/navn`-anropet. En description som oppsummerer stegene er bortkastet kontekst og svekker treffsikkerheten.

```
# RIKTIG (NÅR):  Bruk når en Flyway-migrering skal skrives/endres ... eller /flyway-migration.
# FEIL (HVA):     Denne skillen lager en V<n>__navn.sql-fil, kjører gradle flywayMigrate, og ...
```

Regler for description:
- **Front-load det ledende ordet** — det første ordet gjør invokasjonsjobben.
- **Én trigger per gren.** Synonymer som omdøper samme situasjon er **duplisering** — kollaps dem, behold bare reelt ulike triggere.
- **Kutt identitet som allerede står i kroppen.** Behold triggere, pluss en eventuell "når en annen skill trenger…"-klausul.

## Progressive disclosure — kort topp, tung referanse i egne filer

Et SKILL.md skal være legibelt på ett skjermbilde av blikk. Materiale rangeres på et **informasjonshierarki**:

1. **Steg i SKILL.md** — ordnede handlinger Copilot gjør, i rekkefølge. Hvert steg ender på et **fullføringskriterium** som er _sjekkbart_ (kan Copilot se ferdig fra ikke-ferdig?) og, der det teller, _uttømmende_ ("hver berørt modul gjort rede for", ikke "lag en endringsliste").
2. **Referanse i SKILL.md** — definisjoner, regler, fakta som slås opp ved behov.
3. **Ekstern referanse** — tungt materiale skjøvet ut i en `references/`-fil, nådd via en **kontekst-peker** og lastet kun når pekeren fyrer.

**Progressive disclosure** er flyttet nedover stigen: ut av SKILL.md og inn i en lenket fil, så toppen holder seg lesbar. Testen er **forgrening** — inline det hver gren trenger, skyv bak en peker det bare noen grener når. Slik gjør søsken-skillene det allerede: `kotlin-ktor` holder feilkontrakt og paginering i `references/`, `tdd` skyver mocking-eksempler ut. Følg det mønsteret — ikke la SKILL.md svulme (**sprawl**).

Pekerens _ordlyd_, ikke målet, avgjør hvor pålitelig Copilot når materialet. Skriv pekere som "Se `references/x.md` for full implementasjon (A, B, C)", ikke bare en naken lenke.

## Kontrakter framfor forbud

Hus-mønsteret her er **kontrakt**, ikke forbudsliste. En kontrakt sier hva som må holde (positivt, sjekkbart); en forbudsliste rams opp hva man ikke skal gjøre (åpen, lett å omgå). Se `grill-with-docs` ("Kontrakt for økta", "ADR-kontrakt") og `tdd` ("Sjekkliste per syklus"). Skriv kontrakter, ikke "ikke gjør X" der det lar seg gjøre. Et forbud beholdes bare når det fanger et konkret anti-mønster Copilot ellers faller i (f.eks. "endre aldri en allerede deployet Flyway-migrering").

## Ledende ord

Et **ledende ord** er et kompakt begrep som allerede bor i modellens forhåndstrening (_tracer bullet_, _idempotent_, _grill_, _vertikalt snitt_) og som forankrer en hel atferd i få tokens. Det tjener forutsigbarhet to ganger: i kroppen styrer det _utførelse_ (samme atferd hver gang ordet dukker opp), i description styrer det _invokasjon_ (når samme ord lever i teamets prompts, kode og docs, kobler Copilot språket til skillen og fyrer mer pålitelig). Jakt på restatementer som et ledende ord kan pensjonere — "rask, deterministisk, lav overhead" → _tight_.

## Stramt og konkret

- **Én sannhetskilde.** Hver betydning bor ett sted; å endre atferd skal være én redigering.
- **Jakt no-ops setning for setning.** En linje modellen alt følger som default koster kontekst og sier ingenting — slett hele setningen, ikke trim ord. Vær aggressiv.
- **Konkret over generisk.** Ekte stier (`src/main/resources/db/migration`), ekte kommandoer (`./gradlew test`), ekte typer (`ApiErrorException`, `PaginatedResponse<T>`). Ingen plassholdere som "kjør testene dine".

## Arbeidsflyt — skrive eller forbedre en skill

### 1. Avklar formålet og det ledende ordet
Hva er den ene jobben skillen gjør, og hvilket **ledende ord** bærer den? Kan du ikke navngi jobben i én setning, er skillen for bred — splitt. Bruk `/grill-with-docs` hvis formålet selv trenger stresstesting, og `/domain-modeling` for å låse domeneord skillen skal bruke.

### 2. Skriv description (NÅR) først
Før kroppen: list **trigger-signalene**. Én per reell gren, ledende ord først, `/navn`-anropet til slutt. Hold deg til `name` + `description`.

### 3. Plasser innholdet på stigen
Skriv stegene/kontraktene som hver gren trenger inline. Skyv tung referanse (full kode, lange tabeller, edge-case-kataloger) til `references/<navn>.md` med en presis kontekst-peker. Bind skillen til `.grill/`-artefakter der den henger sammen med faseløkka:
- `docs/CONTEXT.md` — valgt tilnærming, så skillen bruker samme vokabular.
- `docs/adr/` — beslutninger skillen må respektere (ikke reåpne avgjorte valg).
- `docs/GLOSSARY.md` — domenespråk skillen skal skrive i.
- `.grill/PLAN.md` / `.grill/VERIFICATION.md` / `.grill/STATE.md` — input/utfall for skills som lever i @grillmester sin faseløkke (jf. `to-issues`, `tdd`).

### 4. Sett sjekkbare fullføringskriterier
Hvert steg ender på en betingelse Copilot kan verifisere. For dette repoet er den deterministiske gaten oftest `./gradlew test` / `./gradlew build` med ferskt output — ingen "ser riktig ut".

### 5. Prun
Gå gjennom hver setning: er den **relevant**? Er den en **no-op**? Er meningen duplisert et annet sted? Slett aggressivt. Mål SKILL.md mot søsken-skillene — er den vesentlig lengre uten å gjøre mer, skyv ned eller splitt.

### 6. Verifiser triggeren
Les description som om du var Copilot midt i en oppgave: ville den fyrt på de reelle situasjonene, og _ikke_ fyrt på naboene? Overlapper triggeren med en eksisterende skill, skjerp begge så grensen er skarp.

## Når du skal splitte

**Granularitet** koster: hver ny skill legger sin `description` til konteksten Copilot alltid bærer. Splitt bare når kuttet fortjener det:
- **Etter invokasjon** — skill ut et stykke når det har et eget **ledende ord**/trigger som bør fyre selvstendig, eller en annen skill må nå det.
- **Etter sekvens** — del en lang stegrekke når stegene lenger fram frister Copilot til å haste gjennom det den står i (**premature completion**); skjules de, gjør den mer **legwork** på gjeldende steg.

## Feilmodi

Bruk disse til å diagnostisere en skill som ikke oppfører seg — full katalog i [references/vokabular-og-feilmodi.md](references/vokabular-og-feilmodi.md): **premature completion**, **duplisering**, **sediment**, **sprawl**, **no-op**. Førstelinjeforsvar er nesten alltid å skjerpe fullføringskriteriet (billig, lokalt) før du splitter.
