---
name: issue-management
description: "Bruk når et GitHub-issue på navikt/syfo-budstikka skal opprettes eller håndteres gjennom livsløpet sitt — epic + sub-issues, avhengigheter, prosjektboard-status, ferdigmelding og PR-kobling. Typisk når noen sier 'lag et issue', 'opprett en sak/epic', 'koble disse issuene', 'sett issuet i gang', 'meld ferdig', eller når @grillmester skal spore arbeid mot tracker. For å bryte en hel PLAN ned i mange snitt: bruk /to-issues i stedet."
---

# Issue-håndtering

Opprett og håndter individuelle GitHub Issues på `navikt/syfo-budstikka` — med epics, sub-issues, avhengigheter, prosjektboard og ferdigmelding.

Dette er **mekanikk-skillen** for issue-livsløpet. Skal en hel `.grill/PLAN.md` brytes ned i mange vertikale snitt, gjør `/to-issues` det grovarbeidet først; denne skillen håndterer det enkelte issuet derfra (struktur, kobling, status, lukking). I @grillmester sin faseløkke hører den hjemme i plan- og serveringsfasen.

## Arbeidsflyt

### 1. Sjekk om issue allerede finnes

Før du oppretter et nytt issue: sjekk om brukeren allerede har referert til ett (f.eks. `#123` eller en GitHub-URL). Hvis ja, bruk det eksisterende. Sjekk også `.grill/PLAN.md` / `.grill/STATE.md` — snittet kan allerede være sporet der.

### 2. Velg type

| Type | Bruk |
|------|------|
| **Epic** | Stor oppgave som brytes ned i flere issues |
| **Feature** | Ny funksjonalitet (nytt endepunkt, ny Kafka-konsument, nytt datalager) |
| **Story** | Brukerhistorie / brukstilfelle |
| **Task** | Teknisk oppgave: Flyway-migrasjon, NAIS-config, refaktorering, oppgradering, chore |
| **Bug** | Feil som må fikses |

### 3. Opprett issue med riktig struktur

Hvis repoet har issue-maler i `.github/ISSUE_TEMPLATE/` for den valgte typen, les feltstrukturen fra malen og lag en markdown-body med tilsvarende seksjoner. Finnes ingen mal, bruk en kort, fast struktur: **Hva**, **Hvorfor**, **Akseptansekriterier**, og for backend-arbeid relevante lag (migrasjon / route / konsument / auth / test / NAIS).

Bruk domenespråket fra `docs/GLOSSARY.md` i titler og beskrivelser når det finnes, og respekter beslutninger i `docs/adr/` for området du rører.

Inkluder alltid når relevant:
- **Avhengigheter:** `Blokkert av #NNN`
- **Epic-kobling:** `Del av epic: #EPIC_NUMMER`

### 4. Opprett issue

**MCP (foretrukket):** Bruk issue-/project-verktøy for å opprette issue med riktig type direkte.

**Fallback (`gh api`):**
```bash
gh api repos/navikt/syfo-budstikka/issues \
  -X POST \
  -f title="Persistér mottatt budstikke-hendelse" \
  -f body="BODY" \
  -f type="Task" \
  --jq '.html_url'
```

Se `references/issue-types.md` for native typer og hvordan du oppdager tilgjengelige typer i `navikt`-orgen.

### 4b. Legg issue inn i prosjektboard (hvis konfigurert)

Programmatisk opprettelse (MCP/REST/`gh api`) auto-knytter ikke issuet til et prosjekt slik web-UI gjør. Kjør derfor prosjekt-steget etterpå:

- Les issue-malen i repoet og se etter en `projects:`-linje. Mangler malen eller linjen → hopp over hele prosjektflyten uten å feile.
- Er prosjekt konfigurert: legg issuet inn og oppdag prosjekt, felter og opsjoner dynamisk. Hardkod aldri felt-ID-er, option-ID-er eller statusnavn.

Se `references/projects.md` for sekvens, auth-preflight og feilhåndtering.

### 5. Epic-håndtering

For store oppgaver som brytes ned:

1. Opprett epic-issuet først
2. Opprett underliggende issues (ofte ferdig tegnet av `/to-issues` som vertikale snitt)
3. Koble sub-issues til epicen via GitHubs sub-issues-API (`references/sub-issues.md`)
4. Koble avhengigheter via dependencies-API-et (`references/dependencies.md`)
5. Behold også `Del av epic: #EPIC_NUMMER` og `Blokkert av #NNN` i issue-teksten for lesbarhet

#### Sub-issues skal være selvstendige

Hvert sub-issue skal kunne plukkes uten å lese hele epicen:
- Tydelig beskrivelse av hva som skal gjøres
- Berørte filer og lag (`src/main/kotlin/no/nav/syfo/...`, Flyway-migrasjon, NAIS-manifest)
- Avhengigheter til andre issues
- Akseptansekriterier som er testbare (`./gradlew test`)

### 6. Epic-workflow og progresjon

Når en epic skal løses stegvis:

1. **Les epicen** — hent epic, sub-issues og avhengighetsinformasjon
2. **Kategoriser åpne sub-issues:**
   - **Kjørbar nå** — alle avhengigheter er oppfylt
   - **Blokkert** — minst én avhengighet er fortsatt åpen
   - **Parallelle kandidater** — flere kjørbare oppgaver uten innbyrdes avhengighet
3. **Presenter anbefaling:**
   - Én kandidat → foreslå den
   - Flere kandidater → foreslå valgbare eller parallelle alternativer
   - Ingen kandidater → forklar hva som blokkerer
4. **Løs oppgaven** — følg normal implementeringsflyt (`/tdd` + relevante domeneskills som `/kotlin-ktor`, `/flyway-migration`, `/kafka-topic`, `/nais-manifest`)
   - Når arbeid starter, sett prosjekt-status til `In Progress`/tilsvarende via mønsteret i `references/projects.md`
   - Er repo, prosjekt eller statusfelt ikke konfigurert, hopp over uten å feile
5. **Gjenta** — etter fullføring, vurder neste kjørbare oppgave

### 7. Ferdigmelding på issues

Etter at et issue er løst, legg igjen en ferdigmelding. Hent fakta fra `.grill/VERIFICATION.md` der den finnes:

```bash
gh issue comment ISSUE_NUMMER --repo navikt/syfo-budstikka --body "COMMENT_BODY"
```

Strukturert og kortfattet:

~~~markdown
## ✅ Løst

**Oppsummering:** [Kort beskrivelse av hva som ble gjort]

**Endrede filer:**
- `src/main/kotlin/no/nav/syfo/...Kt` — [hva som ble endret]
- `src/main/resources/db/migration/VNN__...sql` — [Flyway-migrasjon]
- `.nais/nais.yaml` — [topic / accessPolicy ved behov]

**Verifisering:** `./gradlew test` (+ build/lint) — [pass/fail + exit-kode, eller `Ikke kjørt` + grunn]

**Inspeksjon:** [Godkjent / Godkjent med merknader / Må følges opp] — [kort oppsummering, evt. fra grill-inspektor]

**PR:** #PR_NUMMER
~~~

Hold inspeksjonsoppsummeringen kort — ikke en full rapport med mindre brukeren ber om det.

### 8. Lukk epic automatisk

Etter at et sub-issue er lukket, sjekk om alle sub-issues i epicen er fullført. Gjenstår ingen åpne:
1. Legg igjen en oppsummerende kommentar på epicen
2. Lukk epicen

### 9. Issue-kobling i PR-er

Når arbeidet resulterer i en PR (typisk via `/pull-request` i serveringsfasen):

```
Closes #ISSUE_NUMMER
```

Skal issuet holdes åpent:

```
Relates to #ISSUE_NUMMER
```

`Closes #...` flytter normalt issuet til `Done` i Projects V2 av seg selv — ikke bygg egen Done-logikk med mindre boardet krever det (se `references/projects.md`).

## Beslutningstre

```
Er oppgaven stor nok for en epic?
├── Ja → Opprett Epic + underliggende issues
│         (bruk /to-issues for å tegne vertikale snitt fra .grill/PLAN.md)
│   └── Hvert sub-issue: selvstendig, med avhengigheter og akseptansekriterier
└── Nei → Opprett frittstående issue
    └── Type? → Feature / Story / Task / Bug
```
