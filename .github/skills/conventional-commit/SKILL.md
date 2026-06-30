---
name: conventional-commit
description: "Brukes når du skal lage en commit eller skrive en commit-melding i dette Ktor-backend-repoet. Trigger-signaler: «commit», «commit-melding», «git commit», «hva skal stå i committen», eller når staged changes er klare og skal beskrives."
---

# Conventional commit

Generer commit-meldinger etter Conventional Commits-spesifikasjonen, tilpasset dette repoet.

## Format

```
<type>(<scope>): <beskrivelse>

[valgfri body]

[valgfri footer]
```

## Typer

| Type | Brukes når |
|---|---|
| `feat` | Ny funksjonalitet |
| `fix` | Bugfiks |
| `docs` | Kun dokumentasjonsendringer |
| `style` | Formatering, importer, ktlint (ingen kodeendring) |
| `refactor` | Kode som verken fikser bug eller legger til feature |
| `perf` | Ytelsesendringer |
| `test` | Legge til eller fikse tester |
| `build` | Endringer i Gradle, avhengigheter eller Dockerfile |
| `ci` | Endringer i GitHub Actions / workflows |
| `chore` | Andre endringer som ikke påvirker kjørbar kode |

## Scopes for dette repoet

Velg scope etter hvilket teknisk område i backendet endringen treffer:

```
feat(routing): legg til endepunkt for henting av budskap
fix(auth): valider audience-claim for TokenX-token
feat(kafka): konsumer hendelser fra teamsykefravaer-topic
fix(db): rett feil i Flyway-migrasjon for budskap-tabellen
refactor(plugin): flytt statuspages-konfig til egen modul
test(routing): legg til integrasjonstest med testApplication
build(deps): oppgrader Ktor til 3.x via versjonskatalog
ci(deploy): legg til steg for NAIS prod-deploy
perf(db): legg til indeks på fnr-kolonnen
chore(nais): juster ressursgrenser i nais.yaml
docs(readme): dokumenter lokal oppstart med docker-compose
```

Vanlige scopes: `routing`, `auth`, `kafka`, `db`, `flyway`, `plugin`, `config`, `nais`, `deps`. Bruk pakke- eller domenenavn (f.eks. `budskap`) når det er mer presist.

## Breaking changes

Marker med `!` etter scope og forklar konsekvensen for konsumenter i `BREAKING CHANGE:`-footeren:

```
feat(routing)!: endre responsformat for budskap-endepunktet

BREAKING CHANGE: Feltet `opprettet` er endret fra epoch-millis til ISO-8601.
Konsumenter må oppdatere sin deserialisering.
```

## Regler

- Første linje: maks 72 tegn
- Bruk imperativ: «legg til», ikke «la til» / «legger til»
- Ikke avslutt emnelinjen med punktum
- Hold deg konsekvent til norsk i hele meldingen
- Referer til GitHub-issue i footer: `Closes #123` / `Fixes #456`
- Ta alltid med `Co-authored-by`-trailer for Copilot

## Arbeidsflyt

### 1. Analyser staged changes

```bash
git diff --cached --stat        # Oversikt over endrede filer
git diff --cached               # Detaljert diff
```

### 2. Finn type og scope

Basert på diff-en:
1. Identifiser **type** (feat/fix/refactor osv.)
2. Identifiser **scope** (hvilket teknisk område, se tabellen over)
3. Skriv en kort og presis beskrivelse

### 3. Skriv commit-melding

```bash
git commit -m "type(scope): kort beskrivelse" \
  -m "Utdypende forklaring hvis nødvendig." \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### 4. Flere logiske endringer

Hvis staged changes inneholder flere urelaterte endringer:
1. Foreslå å dele dem opp i egne commits
2. Bruk `git add -p` for å stage deler av endringene
3. Lag én commit per logisk endring

## Sikkerhetsprotokoll

Før du committer, verifiser at staged changes **IKKE** inneholder:
- Tokens, API-nøkler eller credentials (TokenX/Azure AD client secrets, JWK-er)
- Passord eller secrets (også i kommentarer og `application.yaml`)
- PII: fødselsnumre, aktør-id, navn eller e-post i testdata og logback-oppsett
- `.env`-filer eller NAIS-secrets med reelle verdier

Hvis du oppdager sensitive data: **STOPP** og varsle brukeren.

## Eksempler

```bash
# Enkel feature
git commit -m "feat(routing): legg til health-endepunkt for NAIS" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

# Bugfiks med issue-referanse
git commit -m "fix(auth): håndter utløpt TokenX-token" \
  -m "Token-validering kastet 500 i stedet for 401 ved utløpt token,
som ga uklare feilmeldinger til konsumenter." \
  -m "Fixes #456

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

# Oppgradering av avhengighet
git commit -m "build(deps): oppgrader postgresql-driver til 42.7.4" \
  -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"

# Breaking change
git commit -m "feat(routing)!: fjern deprecated /api/v1/budskap" \
  -m "BREAKING CHANGE: /api/v1/budskap er fjernet. Bruk /api/v2/budskap.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```
