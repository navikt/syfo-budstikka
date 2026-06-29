# Grillmester — Copilot-agentoppsett for syfo-budstikka

Et høykvalitets GitHub Copilot-oppsett for dette Ktor-backend-repoet (Kotlin, NAV / `no.nav.syfo`). Det bygger bro mellom tre kilder: research på agent-optimalisering, etablerte agent-flyt-mønstre fra åpne kilder, og NAV-teamets eksisterende «hovmester»-skills — alt reframet Copilot-native (`.agent.md` / `.instructions.md` / `SKILL.md`).

## Agenter (`.github/agents/`)
- **@grillmester** (Opus 4.8) — orkestrator + **inline implementør**. Kjører en faseløkke: grill → design → plan → implementer → verifiser → server. Skriver arbeidsminne til `.grill/`.
- **grill-inspektor** (GPT-5.5, internt) — fersk **kryssmodell-reviewer**. **Opt-in**, anbefalt-på for høyrisiko. Her bevares «begge modellfamilier ser på arbeidet», men kostnadsstyrt.

### Designprinsipper (hvorfor det er bygd slik)
1. **Skriveren er inline** på sterk modell — koding parallelliseres ikke (implisitte beslutninger kolliderer).
2. **Subagenter = kontekst-verktøy**, kun til read-only utforsking + kryssmodell-verify.
3. **Sterke modeller, ingen svake tier.** Kostnadskontroll skjer via opt-in på de dyre stegene, ikke ved å svekke modellen. (Ingen «juniorkokk».)
4. **Kvalitetsgater er deterministiske og utenfor modellen** — `./gradlew test`, lint, build + `scripts/validate-agent-models.sh`. Positivt bevis, ikke «ser riktig ut».
5. **Disk er minne** (`.grill/`), ikke samtalen. Checkpoint ved ~55 % vindu-okkupasjon.
6. **Kontrakter, ikke forbud** i alle instruksjoner.

## `.grill/` — arbeidsminne på disk
Opprettes av @grillmester per oppgave: `STATE.md` + `MODELL-STATUS.md` (transiente, gitignorert), `CONTEXT.md`, `GLOSSARY.md`, `DECISIONS.md`, `PLAN.md`, `VERIFICATION.md` (append-only gate-bevis), `REVIEW.md` (kryssmodell-verdikt), `adr/NNNN-*.md`. ADR/glossar/CONTEXT er verdt å committe; `STATE.md`/`MODELL-STATUS.md` er transiente.

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
Path-scopede via `applyTo`: `kotlin` (`**/*.kt`), `security`, `github-actions`, `docker`, `norwegian-text`, `copilot-review`.

## Status og empirisk verifisering (gjenstår)

Dette er en eksperimentell testbenk. To ting hviler på Copilot-plattformevner som må bekreftes empirisk (CLI + VS Code) før de regnes som bevist:
- **Eksakt `model:`-streng** Copilot aksepterer i agent-frontmatter (`claude-opus-4.8` / `gpt-5.5`). Modell-gaten validerer at pinnen står på allowlist, ikke at runtime faktisk bruker den.
- **Agent-til-agent-delegering:** hele fase 5 kryssmodell-review forutsetter at `@grillmester` kan kalle `grill-inspektor` (annen modellfamilie). Hvis Copilot ikke støtter det, er fallback `/review` (selvreview) + de deterministiske gatene — som bærer kvalitet uansett — og manuell kryssmodell-review.

Ikke aktivt i dette repoet ennå: **fase 6–7 deploy** (krever `.nais/nais.yaml` + deploy-workflow når tjenesten er reell — mønsteret ligger i `github-actions.instructions.md`), og repo-herding som CODEOWNERS/branch protection, Dependabot og PR-mal.

## Proveniens
Bygd ved å adaptere hovmesters backend-skills (Ktor-tunet) + etablerte flyt-mønstre for design/implementering/review (oversatt og NAV/Ktor-tilpasset) + research-funn (context-rot, sterk-modell-inline, deterministiske gater, disk-som-minne, kontrakter-ikke-forbud). Eksperimentell testbenk — meningen er å bevise mønstrene her før de evt. graderes inn i hovmester.
