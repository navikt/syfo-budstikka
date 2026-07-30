---
name: writing-great-skills
description: "Bruk når du skal skrive en ny Copilot-skill, endre eller forbedre en eksisterende skill under .github/skills/ i dette repoet, eller vurdere om en skill trigger riktig — typisk når noen sier 'lag en skill', 'skriv en copilot-skill', 'forbedre denne skillen', 'hvorfor fyrer ikke skillen', eller /writing-great-skills."
---

# Skrive gode skills (syfo-budstikka)

A skill exists to make a stochastic system follow a predictable *process*,
not to force identical output. Repository skills live in
`.github/skills/<name>/SKILL.md`, follow
`docs/agents/language-policy.md`, and target this repository's stack (see
`copilot-instructions.md`).

Vokabular i **fet skrift** er definert i [references/vokabular-og-feilmodi.md](references/vokabular-og-feilmodi.md) — slå opp der når du trenger full betydning eller skal diagnostisere en skill som ikke oppfører seg.

## Frontmatter — supported fields

`name` and `description` are required. Use only frontmatter fields supported by
GitHub Copilot CLI; do not invent YAML fields.

- **`name`** — the kebab-case directory name and `/slash` name.
- **`description`** — briefly says when the skill is useful, not what its full
  workflow does. For a model-reachable skill, this is Copilot's discovery
  signal and carries **trigger signals**. For a manual-only skill, it is a
  concise human-facing summary in the skill list; do not pack it with automatic
  triggers the model cannot use.

Set manual, model, or shared invocation with the supported fields in
`docs/agents/skill-invocation.md`. Use other supported interface fields, such
as `argument-hint`, only when they express a real user interface; consult the
official reference linked from that document.

```
# RIGHT (WHEN): Use when writing or changing a Flyway migration ... or /flyway-migration.
# WRONG (WHAT): This skill creates V<n>__name.sql and runs gradle flywayMigrate ...
```

Rules for a model-reachable description:

- **Front-load the leading word** — the first word does the invocation work.
- **One trigger per branch.** Synonyms that merely rename the same situation
  are **duplication**; collapse them and keep only genuinely different
  triggers.
- **Remove identity already carried by the body.** Keep triggers and, when
  needed, a clause for another skill depending on this one.

## Progressive disclosure — kort topp, tung referanse i egne filer

Et SKILL.md skal være legibelt på ett skjermbilde av blikk. Materiale rangeres på et **informasjonshierarki**:

1. **Steg i SKILL.md** — ordnede handlinger Copilot gjør, i rekkefølge. Hvert steg ender på et **fullføringskriterium** som er _sjekkbart_ (kan Copilot se ferdig fra ikke-ferdig?) og, der det teller, _uttømmende_ ("hver berørt modul gjort rede for", ikke "lag en endringsliste").
2. **Referanse i SKILL.md** — definisjoner, regler, fakta som slås opp ved behov.
3. **Ekstern referanse** — tungt materiale skjøvet ut i en `references/`-fil, nådd via en **kontekst-peker** og lastet kun når pekeren fyrer.

**Progressive disclosure** er flyttet nedover stigen: ut av SKILL.md og inn i en lenket fil, så toppen holder seg lesbar. Testen er **forgrening** — inline det hver gren trenger, skyv bak en peker det bare noen grener når. Slik gjør søsken-skillene det allerede: `kotlin-ktor` holder feilkontrakt og paginering i `references/`, `tdd` skyver mocking-eksempler ut. Følg det mønsteret — ikke la SKILL.md svulme (**sprawl**).

Pekerens _ordlyd_, ikke målet, avgjør hvor pålitelig Copilot når materialet. Skriv pekere som "Se `references/x.md` for full implementasjon (A, B, C)", ikke bare en naken lenke.

## Contracts over prohibitions

The repository pattern is a **contract**, not a prohibition list. A contract
states what must hold in positive, checkable terms; a prohibition list remains
open-ended and easy to route around. The one-question and confirmation gates
in `grilling` and the per-cycle checklist in `tdd` are examples. Keep a
prohibition only when it captures a concrete anti-pattern Copilot otherwise
falls into, such as changing an already deployed Flyway migration.

## Ledende ord

Et **ledende ord** er et kompakt begrep som allerede bor i modellens forhåndstrening (_tracer bullet_, _idempotent_, _grill_, _vertikalt snitt_) og som forankrer en hel atferd i få tokens. Det tjener forutsigbarhet to ganger: i kroppen styrer det _utførelse_ (samme atferd hver gang ordet dukker opp), i description styrer det _invokasjon_ (når samme ord lever i teamets prompts, kode og docs, kobler Copilot språket til skillen og fyrer mer pålitelig). Jakt på restatementer som et ledende ord kan pensjonere — "rask, deterministisk, lav overhead" → _tight_.

## Stramt og konkret

- **Én sannhetskilde.** Hver betydning bor ett sted; å endre atferd skal være én redigering.
- **Jakt no-ops setning for setning.** En linje modellen alt følger som default koster kontekst og sier ingenting — slett hele setningen, ikke trim ord. Vær aggressiv.
- **Konkret over generisk.** Ekte stier (`src/main/resources/db/migration`), ekte kommandoer (`./gradlew test`), ekte typer (`ApiErrorException`, `PaginatedResponse<T>`). Ingen plassholdere som "kjør testene dine".

## Arbeidsflyt — skrive eller forbedre en skill

### 1. Avklar formålet og det ledende ordet
Hva er den ene jobben skillen gjør, og hvilket **ledende ord** bærer den? Kan du ikke navngi jobben i én setning, er skillen for bred — splitt. Bruk `/grill-with-docs` hvis formålet selv trenger stresstesting, og `/domain-modeling` for å låse domeneord skillen skal bruke.

### 2. Write discovery and invocation first

Before the body, choose the invocation boundary using
`docs/agents/skill-invocation.md`. For a model-reachable skill, put one genuine
**trigger signal** per branch in `description`, leading-word first. For a
manual-only skill, write only a precise human-facing summary.

### 3. Place content on the disclosure ladder

Keep the steps and contracts every branch needs in `SKILL.md`. Move heavy
material such as full examples, long tables, and edge-case catalogues to
`references/<name>.md` behind a precise contextual pointer. Route repository
documentation through `docs/agents/domain.md`:

- `docs/glossary.md` owns canonical domain language.
- Explicitly relevant files in `docs/adr/` own binding, hard-to-reverse
  decisions.
- `docs/context.md` is read or updated only for repository orientation or
  overall status.
- A skill uses transient workflow artifacts only when their owning agent
  contract explicitly requires them; do not invent a global artifact suite.

### 4. Sett sjekkbare fullføringskriterier
Hvert steg ender på en betingelse Copilot kan verifisere. For dette repoet er den deterministiske gaten oftest `./gradlew test` / `./gradlew build` med ferskt output — ingen "ser riktig ut".

### 5. Prun
Gå gjennom hver setning: er den **relevant**? Er den en **no-op**? Er meningen duplisert et annet sted? Slett aggressivt. Mål SKILL.md mot søsken-skillene — er den vesentlig lengre uten å gjøre mer, skyv ned eller splitt.

### 6. Verify discovery

For a model-reachable skill, read `description` as Copilot in the middle of a
task: would it select the skill for the real situations and not its neighbours?
For a manual-only skill, verify that the picker summary distinguishes it from
nearby commands without pretending it can auto-trigger.

## Når du skal splitte

**Granularitet** koster: hver ny skill legger sin `description` til konteksten Copilot alltid bærer. Splitt bare når kuttet fortjener det:
- **Etter invokasjon** — skill ut et stykke når det har et eget **ledende ord**/trigger som bør fyre selvstendig, eller en annen skill må nå det.
- **Etter sekvens** — del en lang stegrekke når stegene lenger fram frister Copilot til å haste gjennom det den står i (**premature completion**); skjules de, gjør den mer **legwork** på gjeldende steg.

## Feilmodi

Bruk disse til å diagnostisere en skill som ikke oppfører seg — full katalog i [references/vokabular-og-feilmodi.md](references/vokabular-og-feilmodi.md): **premature completion**, **duplisering**, **sediment**, **sprawl**, **no-op**. Førstelinjeforsvar er nesten alltid å skjerpe fullføringskriteriet (billig, lokalt) før du splitter.
