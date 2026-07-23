# 0010: Kontraktbibliotek som eget publisert artefakt

- Status: besluttet (B63; grillet 2026-07-22)
- Dato: 2026-07-22
- Relatert: B22/B23/B30/B43/B54, ADR 0011 (kontrakt-API + DSL), `docs/kontrakt.md`

## Kontekst

Kontrakten (`no.nav.budstikka.domain.dispatch`: konvolutt `Dispatch`, sealed `DispatchContent`,
felles typer, `dispatchJson`, `DispatchHeader`) er i dag intern i budstikka-appen. B30 og B54
forutsetter allerede et «delt kontraktbibliotek» som produsent-appene kan kompilere mot
(kompileringstid-validering, delt header-konstant), men selve pakkingen var aldri grillet.

Kontrakten er rent ekstraherbar: pakken importerer ingenting fra resten av `no.nav.budstikka`,
og avhenger kun av kotlinx-serialization + stdlib `kotlin.time.Instant`. 12 interne filer
konsumerer typene, så app-modulen vil avhenge av det ekstraherte biblioteket.

Tre valg måtte festes:

1. Hvor biblioteket bor og bygges (topologi).
2. Hvor det publiseres og hva det heter (mål + koordinater).
3. Hvordan det versjoneres i forhold til appen og wire-`.v1`.

## Beslutning

### 1. Multi-modul i samme repo

Kontrakten ekstraheres til et eget Gradle-subprosjekt `:kontrakt` (Kotlin-only, ingen
Ktor/Exposed/Kafka/Flyway). App-modulen avhenger av det via `implementation(project(":kontrakt"))`,
og bygget publiserer **kun** `:kontrakt`-artefaktet.

Kontrakten er budstikkas sannhet og eneste interne konsument; å holde dem i samme repo lar dem
kompileres og testes atomisk i samme PR (kompilatoren fanger brudd umiddelbart), uten to-repo
versjons-sync.

### 2. GitHub Packages, `no.nav.syfo:budstikka-kontrakt`

Publiseres til GitHub Packages (`maven.pkg.github.com/navikt/syfo-budstikka`) via `maven-publish`
og GitHub Actions `GITHUB_TOKEN` (`packages: write`) — ingen egne secrets. Konsumentene henter via
`github-package-registry-mirror.gc.nav.no` (allerede i `settings.gradle.kts`).

Koordinater: group `no.nav.syfo` (matcher repoet), artifactId `budstikka-kontrakt`. Gradle-modulnavnet
`:kontrakt` overstyres til `budstikka-kontrakt` i publiseringsblokka.

### 3. Uavhengig, tag-drevet semver (0.x fram til prod)

`:kontrakt` har egen `version`, frikoblet fra app-modulens `1.0.0-SNAPSHOT`. Release trigges av en
git-tag (`kontrakt/vX.Y.Z`) som en GitHub Actions-jobb leser og publiserer fra — app-ens CI/CD røres
ikke. Default `SNAPSHOT` lokalt.

Versjonssemantikk (to distinkte akser — må ikke forveksles):
- **Artefakt-semver** sporer Kotlin-API + serialiseringsform i lib-en.
- **Wire-versjon** (`team-esyfo.budstikka.v1`, B43) sporer on-the-wire topic-kontrakten.
- Additivt felt → artefakt minor-bump, wire forblir `.v1` (trygt fordi `ignoreUnknownKeys=true`).
  Breaking wire-endring → wire `.v2` (dual-write, B43) **og** artefakt-major. En Kotlin-rename uten
  `@SerialName`-endring er API-breaking men wire-kompatibel.
- **0.x fram til prod:** minor-bump (`0.3.0→0.4.0`) *kan* være breaking (tar inn nye/endrede
  meldingstyper mens vi stabiliserer); patch er additivt/fiks. Ved prod kuttes `1.0.0` og streng
  semver slår inn.

## Konsekvenser

- Repoet går fra single- til multi-modul (`settings.gradle.kts` får `include(":kontrakt")`,
  build-scriptet splittes). Engangs-mekanikk.
- Produsent-appene kan kompilere mot typene → kompileringstid-feil ved kontraktbrudd (B30/B54).
- Artefaktet er tynt: avhenger kun av kotlinx-serialization (`api`) + stdlib. Ingen transport-
  eller infra-kobling lekker til konsumentenes classpath.
- Kontrakt-releaser er en egen, bevisst handling (tag) — ikke en bieffekt av app-deploy.

## Alternativer vurdert

- **Separat repo (`syfo-budstikka-kontrakt`):** forkastet — to-repo versjons-dans ved hver
  kontraktendring, og kontrakt + eneste konsument drifter fra hverandre (mot «én kilde», B50).
- **Publisere hele app-jaren:** forkastet — drar Ktor/Exposed/Kafka/Flyway/Postgres inn på hver
  konsuments classpath.
- **Felles versjon app + lib:** forkastet — kobler to helt ulike livssykluser (kontinuerlig
  app-deploy vs. pinnet lib-semver).
- **NAVs interne Maven-proxy/Artifactory:** forkastet — tyngre oppsett uten gevinst når
  konsumentene når GitHub Packages via mirroren uansett.
