# Grillmester — Copilot-agentoppsett for syfo-budstikka

Et høykvalitets GitHub Copilot-oppsett for dette repoet, reframet Copilot-native (`.agent.md` / `.instructions.md` / `SKILL.md`). Denne fila er **menneske-vendt**: rasjonale, status og proveniens. Den autoritative kjøre-konfigurasjonen bor i filene den peker til — Copilot laster `copilot-instructions.md` (always-on), agentene i `.github/agents/` og skillene i `.github/skills/`. Ikke dupliser detaljer hit; pek.

## Hva oppsettet er (pekere)
- **Agenter** — `.github/agents/`: `@grillmester` (Opus 4.8, orkestrator + inline implementør, faseløkke grill → design → plan → implementer → verifiser → server) og `grill-inspektor` (GPT-5.5, opt-in read-only kryssmodell-reviewer). Detaljene står i agent-filene.
- **Skills** — `.github/skills/` (~33 stk: design/utforsking, implementering/kvalitet, backend-domene (Ktor/NAV), tverrgående/flyt). De auto-oppdages på `description`-feltet; ingen katalog gjentas her — `ls .github/skills/` er fasiten.
- **Instruksjoner** — `.github/instructions/`: always-on (`security`, `copilot-review`, `bevisst-ai-bruk`) + path-scopede (`kotlin`, `github-actions`, `docker`, `norwegian-text`).
- **Modell-gate** — `scripts/validate-agent-models.sh` (CI, `.github/workflows/build.yml`) validerer at hver agents `model:`-pin står på allowlist, feiler hardt og skriver `.grill/MODELL-STATUS.md`. Degradering oppdages her, aldri av modellen selv.

## Designprinsipper (hvorfor det er bygd slik)
1. **Skriveren er inline** på sterk modell — koding parallelliseres ikke (implisitte beslutninger kolliderer).
2. **Subagenter = kontekst-verktøy**, kun til read-only utforsking, kryssmodell-verify + opt-in divergent design-utforsking (design-it-twice). Aldri parallell skriving av kode.
3. **Sterke modeller, ingen svak tier.** Kostnadskontroll skjer via opt-in på de dyre stegene (kryssmodell-review), ikke ved å svekke modellen.
4. **Kvalitetsgater er deterministiske og utenfor modellen** — `./gradlew test`, lint, build + `scripts/validate-agent-models.sh`. Positivt bevis, ikke «ser riktig ut».
5. **Disk er minne** (`.grill/`), ikke samtalen.
6. **Kontrakter, ikke forbud** i alle instruksjoner.

## Durable vs transient: `docs/` og `.grill/`
- **`docs/`** — committet, discoverable: `docs/adr/NNNN-*.md` (ADR), `docs/GLOSSARY.md` (domenespråk), `docs/CONTEXT.md` (valgt tilnærming).
- **`.grill/`** — gitignorert, transient arbeidsminne per oppgave (`STATE.md`, `PLAN.md`, `VERIFICATION.md`, `REVIEW.md`, `DECISIONS.md`, `MODELL-STATUS.md`). Durabel verdi graduerer til `docs/`; `.grill/` overlever ikke oppgaven. Mekanikken (når den leses/skrives) eies av agent-fila.

## Status og empirisk verifisering (gjenstår)
Eksperimentell testbenk, brukt lokalt via **Copilot CLI** — ikke cloud agent på github.com (den ignorerer uansett `model:` og agent-delegering). GitHub-dokumentasjonen bekrefter at CLI støtter det oppsettet hviler på; det som gjenstår er en lokal røyktest:
- **`model:`-pinning i CLI:** Copilot CLI aksepterer modell-display-navn/vendor-suffiks i agent-frontmatter. Modell-gaten validerer at pinnen står på allowlist, ikke at runtime faktisk bruker den.
- **Lokal subagent-delegering:** fase 5 kryssmodell-review forutsetter at `@grillmester` kaller `grill-inspektor` (annen modellfamilie). Copilot CLI støtter custom agents + `/agent`-delegering lokalt, med eget kontekstvindu og per-agent `model:`; `tools:`-allowlisten som låser `grill-inspektor` read-only gjelder også der. De deterministiske gatene bærer kvalitet uansett.

På plass (committet): `.github/CODEOWNERS`, `.github/dependabot.yml`, `.github/PULL_REQUEST_TEMPLATE.md`. Gjenstår: **fase 6–7 deploy** (krever `.nais/nais.yaml` + deploy-workflow når tjenesten er reell) og branch protection (GitHub-innstilling).

## Proveniens
Bygd ved å adaptere hovmesters backend-skills (Ktor-tunet) + etablerte flyt-mønstre for design/implementering/review (oversatt og NAV/Ktor-tilpasset) + research-funn (context-rot, sterk-modell-inline, deterministiske gater, disk-som-minne, kontrakter-ikke-forbud). Eksperimentell testbenk — meningen er å bevise mønstrene her før de evt. graderes inn i hovmester.
