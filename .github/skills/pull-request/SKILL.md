---
name: pull-request
description: "Bruk når en endring i navikt/syfo-budstikka skal pakkes til en pull request, eller en eksisterende PR skal oppdateres — semantisk tittel, kort beskrivelse, issue-kobling, teststatus (./gradlew test), risiko og reviewer-kontekst. Trigger når noen sier 'opprett PR', 'lag pull request', 'oppdater PR-en', eller etter at et vertikalt snitt er implementert og verifisert i @grillmester sin faseløkke."
---

# Pull request

Opprett konsistente, godt strukturerte pull requests på `navikt/syfo-budstikka` som kobles til issues og bærer nok kontekst til at en reviewer kan godkjenne uten å gjette.

Dette er server-steget i @grillmester sin faseløkke: en endring er implementert og verifisert (`.grill/VERIFICATION.md`), og skal nå over til review. PR-en er der verifiseringsbevis og beslutninger gjøres synlige for et menneske og for Copilot-review.

## PR-tittel

Bruk semantisk commit-format:

```
type(scope): kort beskrivelse
```

- **Typer:** `feat`, `fix`, `refactor`, `chore`, `docs`, `test`, `build`
- **Scope:** modulen eller domenet som endres i `no.nav.syfo` (f.eks. `auth`, `kafka`, `db`, en route-modul)
- Skriv beskrivelsen på norsk, imperativt og kort. Eksempel: `feat(kafka): konsumer sykmelding-topic idempotent`

## PR-tekst

For ikke-trivielle endringer skal teksten kort svare på:

- **Hva** ble endret, og **hvorfor** (motivasjon / hvilket problem snittet løser)
- **Issue-kobling** (se under)
- **Verifisering**: hvilke sjekker som er kjørt og er grønne — `./gradlew build`, `./gradlew test`, eventuell manuell verifisering. Pek på `.grill/VERIFICATION.md` hvis den finnes.
- **Risiko og migrasjon**: Flyway-migrasjon som kjører i prod, endret `accessPolicy`/topic/secret i NAIS-yaml, endret feilkontrakt eller auth — alt som kan slå ut ved deploy
- **Reviewer-kontekst**: hva reviewer bør se nøye på, og hva som bevisst er utelatt

Hold body stram. Lenk til ADR der en beslutning er styrende: `Følger .grill/adr/NNNN-...`.

### Body-mal

```markdown
## Hva og hvorfor
<kort beskrivelse av snittet og motivasjonen>

## Issue
Closes #NNN

## Verifisering
- [x] ./gradlew build grønn
- [x] ./gradlew test grønn (ny test dekker snittet)
- [ ] Manuelt verifisert: <hva>

## Risiko / deploy
<Flyway-migrasjon? NAIS accessPolicy/topic/secret? auth/feilkontrakt? ellers "ingen spesiell risiko">

## For review
<hva reviewer bør se nøye på>
```

## Issue-kobling

| Situasjon | I PR-body |
|-----------|-----------|
| Issue løst fullstendig | `Closes #NNN` |
| Delvis arbeid, issue fortsatt åpent | `Relates to #NNN` |
| Del av epic | `Closes #NNN` + `Del av epic: #MMM` |
| Ingen issue | Skriv motivasjonen direkte i beskrivelsen |

Ett vertikalt snitt fra `/to-issues` bør tilsvare én PR som lukker ett issue.

## Sjekkliste før opprettelse

- [ ] `./gradlew build` og `./gradlew test` er grønne lokalt — ikke åpne PR på rødt
- [ ] Ingen PII (fnr, navn, NAVident, tokens) i logger eller i diffen
- [ ] Auth på plass der relevant (TokenX/Azure AD); beskyttede routes ligger under riktig `authenticate(...)`
- [ ] Flyway-migrasjoner er nye, fortløpende `V<n>__...sql` — aldri endret en migrasjon som kan ha kjørt
- [ ] Ktor-avhengigheter via `ktorLibs.*`, annet via `libs.*` (ikke håndskrevne versjoner)
- [ ] NAIS-yaml oppdatert hvis snittet trenger topic/accessPolicy/secret

## Opprettelse

### MCP / PR-verktøy (foretrukket)

Bruk tilgjengelig GitHub-PR-verktøy for å opprette PR med tittel og body mot `navikt/syfo-budstikka`.

### Fallback (gh CLI)

```bash
gh pr create \
  --repo navikt/syfo-budstikka \
  --title "type(scope): beskrivelse" \
  --body-file <fil-med-body>
```

### Oppdatere eksisterende PR

Når en PR allerede finnes og snittet har endret seg — push commit og oppdater body slik at tittel, verifiseringsstatus og risiko fortsatt stemmer:

```bash
gh pr edit <nummer> --repo navikt/syfo-budstikka --title "..." --body-file <fil>
```

## Etter opprettelse

- Vent på CI (`./gradlew build`/test i GitHub Actions) og Copilot-review.
- Adresser review-kommentarer i nye commits på samme branch; oppdater body hvis verifiseringsstatus endrer seg.
- Ved merge-konflikt mot main: bruk `/resolving-merge-conflicts` — ikke `--abort`.
- Squash-merge når review er grønn og checks passerer:

```bash
gh pr merge <nummer> --repo navikt/syfo-budstikka --squash --auto
```

`--auto` lar GitHub merge automatisk så snart checks og påkrevde godkjenninger er på plass.
