# Grillmester — Copilot-agentoppsett for syfo-budstikka

Et høykvalitets GitHub Copilot-oppsett for dette Ktor-backend-repoet (Kotlin, NAV / `no.nav.syfo`). Det bygger bro mellom tre kilder: research på agent-optimalisering, etablerte agent-flyt-mønstre fra åpne kilder, og NAV-teamets eksisterende «hovmester»-skills — alt reframet Copilot-native (`.agent.md` / `.instructions.md` / `SKILL.md`).

## Agenter (`.github/agents/`)
- **@grillmester** (Opus 4.8) — orkestrator + **inline implementør**. Kjører en faseløkke: grill → design → plan → implementer → verifiser → server. Skriver durable docs (ADR/glossar/kontekst) til `docs/` og transient arbeidsminne til `.grill/`.
- **grill-inspektor** (GPT-5.5, internt) — fersk **kryssmodell-reviewer**. **Opt-in**, anbefalt-på for høyrisiko. Her bevares «begge modellfamilier ser på arbeidet», men kostnadsstyrt.

### Designprinsipper (hvorfor det er bygd slik)
1. **Skriveren er inline** på sterk modell — koding parallelliseres ikke (implisitte beslutninger kolliderer).
2. **Subagenter = kontekst-verktøy**, kun til read-only utforsking + kryssmodell-verify.
3. **Sterke modeller, ingen svake tier.** Kostnadskontroll skjer via opt-in på de dyre stegene, ikke ved å svekke modellen. (Ingen «juniorkokk».)
4. **Kvalitetsgater er deterministiske og utenfor modellen** — `./gradlew test`, lint, build + `scripts/validate-agent-models.sh`. Positivt bevis, ikke «ser riktig ut».
5. **Disk er minne** (`.grill/`), ikke samtalen. Checkpoint ved ~55 % vindu-okkupasjon.
6. **Kontrakter, ikke forbud** i alle instruksjoner.

## Filer: `docs/` (durable) vs `.grill/` (transient)
**`docs/`** — committet, discoverable dokumentasjon: `docs/adr/NNNN-*.md` (ADR), `docs/GLOSSARY.md` (domenespråk), `docs/CONTEXT.md` (valgt tilnærming/kontekst). Eksempel fra en grilling-økt: `docs/CONTEXT.md`, `docs/FLYT.md`, `docs/adr/0001-*.md`.
**`.grill/`** — gitignorert, transient arbeidsminne per oppgave: `STATE.md`, `MODELL-STATUS.md`, `PLAN.md`, `VERIFICATION.md` (gate-bevis), `REVIEW.md` (kryssmodell-verdikt), `DECISIONS.md` (beslutningskart). Durabel verdi graduerer til `docs/`; `.grill/` overlever ikke oppgaven.

## Modell-gate
`scripts/validate-agent-models.sh` (kjøres av CI, se `.github/workflows/build.yml`) validerer at hver agents `model:`-pin er på allowlist, feiler hardt, og skriver status til `.grill/MODELL-STATUS.md`. Degradering oppdages her — aldri av modellen selv.

## Skill-katalog (`.github/skills/` — 33 skills)

**Design & utforsking**
`grill-with-docs` (nådeløst design-intervju + ADR/glossar) · `grill-me` (rask plan-stresstest) · `domain-modeling` (ubiquitous language) · `codebase-design` (design i eksisterende kode) · `decision-mapping` (beslutningstre over flere økter) · `prototype` (throwaway-spike) · `to-prd` (kravspec) · `to-issues` (bryt plan i snitt) · `triage` (vurder/klargjør innkommende saker)

**Implementering & kvalitet**
`implement` (steg-for-steg fra PLAN.md) · `tdd` (red-green-refactor, Ktor) · `diagnosing-bugs` (systematisk feilsøk) · `resolving-merge-conflicts` · `improve-codebase-architecture` · `review` (selvreview før kryssreview)

**Backend-domene (Ktor/NAV)**
`kotlin-ktor` · `api-design` · `auth-overview` (TokenX/Azure AD) · `flyway-migration` · `kafka-topic` · `postgresql-review` · `nais-manifest` · `security-review` (PII/DPIA) · `observability-setup` · `nav-architecture-review` (ADR) · `nav-troubleshoot`

**Tverrgående & flyt**
`pull-request` · `conventional-commit` · `issue-management` · `readme-update` · `klarsprak` (norsk mikrotekst) · `handoff` (kontekst-handoff) · `writing-great-skills` (skriv nye skills konsistent)

## Instruksjoner (`.github/instructions/`)
Alltid-på (`applyTo: "**"`): `security`, `copilot-review`, `bevisst-ai-bruk`. Path-scopede: `kotlin` (`**/*.kt`), `github-actions` (`.github/workflows/**`), `docker` (`**/Dockerfile*`), `norwegian-text` (`**/*.md`).

## Status og empirisk verifisering (gjenstår)

Dette er en eksperimentell testbenk, brukt lokalt via **Copilot CLI** — ikke cloud agent på github.com (den brukes ikke, og ignorerer uansett `model:` og agent-delegering). GitHub-dokumentasjonen bekrefter at CLI støtter det oppsettet hviler på; det som gjenstår er en lokal røyktest:
- **`model:`-pinning i CLI:** Copilot CLI aksepterer modell-display-navn/vendor-suffiks i agent-frontmatter (verifisert i bruk). Modell-gaten validerer at pinnen står på allowlist, ikke at runtime faktisk bruker den.
- **Lokal subagent-delegering:** fase 5 kryssmodell-review forutsetter at `@grillmester` kaller `grill-inspektor` (annen modellfamilie). Copilot CLI støtter custom agents + `/agent`-delegering til subagenter som kjører lokalt i sesjonen, med eget kontekstvindu og per-agent `model:`; `tools:`-allowlisten som låser `grill-inspektor` read-only gjelder også i CLI. `/review` (selvreview) er kun en svak forhåndssjekk — ikke en erstatning for kryssmodell (bukken og havresekken). De deterministiske gatene bærer kvalitet uansett.

På plass (committet): `.github/CODEOWNERS`, `.github/dependabot.yml`, `.github/PULL_REQUEST_TEMPLATE.md`. Gjenstår: **fase 6–7 deploy** (krever `.nais/nais.yaml` + deploy-workflow når tjenesten er reell — mønsteret ligger i `github-actions.instructions.md`) og branch protection (GitHub-innstilling, ikke en fil).

## Proveniens
Bygd ved å adaptere hovmesters backend-skills (Ktor-tunet) + etablerte flyt-mønstre for design/implementering/review (oversatt og NAV/Ktor-tilpasset) + research-funn (context-rot, sterk-modell-inline, deterministiske gater, disk-som-minne, kontrakter-ikke-forbud). Eksperimentell testbenk — meningen er å bevise mønstrene her før de evt. graderes inn i hovmester.
