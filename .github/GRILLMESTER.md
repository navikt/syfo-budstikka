# Grillmester — Copilot-agentoppsett for syfo-budstikka

Et høykvalitets GitHub Copilot-oppsett for dette repoet, reframet Copilot-native (`.agent.md` / `.instructions.md` / `SKILL.md`). Denne fila er **menneske-vendt**: rasjonale og navigasjon. Den autoritative kjøre-konfigurasjonen bor i filene den peker til — Copilot laster `copilot-instructions.md` som repository-instruksjon, oppdager agentene i `.github/agents/` og skillene i `.github/skills/`, og laster den valgte agenten eller skillen ved bruk. Ikke dupliser levende status eller detaljer hit; pek.

## Hva oppsettet er (pekere)
- **Agenter** — `.github/agents/`: `@grillmester` er orkestrator og inline implementør; `grill-inspektor` er en opt-in, fersk read-only reviewer. Agentfilene eier roller, deklarerte modellønsker og arbeidsflyt; faktisk runtime-modell må verifiseres separat.
- **Skills** — `.github/skills/`: design/utforsking, implementering/kvalitet, backend-domene (Ktor/NAV) og tverrgående flyt. De auto-oppdages på `description`-feltet; ingen katalog eller opptelling gjentas her — katalogen på disk er fasiten.
- **Instruksjoner** — `.github/instructions/`: always-on og stiavgrensede kontrakter. Filene og deres frontmatter eier den gjeldende inndelingen.
- **Modellpin-gate** — `scripts/validate-agent-models.sh` validerer deklarerte modellpinner mot repoets allowlist i CI og skriver `.grill/MODELL-STATUS.md`. Den beviser ikke runtime-tilgjengelighet, faktisk modellvalg eller fallback. CI-workflowen eier når gaten kjøres; denne fila dupliserer ikke workflow-stien.

## Designprinsipper (hvorfor det er bygd slik)
1. **Skriveren er inline** på sterk modell — koding parallelliseres ikke (implisitte beslutninger kolliderer).
2. **Subagenter = kontekst-verktøy**, kun til read-only utforsking, fersk inspektørreview og opt-in divergent design-utforsking (design-it-twice). Aldri parallell skriving av kode. Inspektørreviewen er kryssmodell bare når runtimebevis bekrefter de deklarerte modellønskene.
3. **Sterke modeller er deklarert, ingen svak tier.** Kostnadskontroll skjer via opt-in på den dyre inspektørreviewen, ikke ved å deklarere en svakere modell.
4. **Kvalitetsgater er deterministiske og utenfor modellen** — `./gradlew test`, lint, build + `scripts/validate-agent-models.sh`. Positivt bevis, ikke «ser riktig ut».
5. **Disk er minne** (`.grill/`), ikke samtalen.
6. **Kontrakter, ikke forbud** i alle instruksjoner.

## Durable vs transient: `docs/` og `.grill/`
- **`docs/`** — committet, discoverable: `docs/adr/NNNN-*.md` (statusmerkede beslutninger; tolking og livsløp eies av `docs/agents/domain.md`), `docs/glossary.md` (domenespråk), `docs/context.md` (orientering og overordnet status, ikke krav eller oppgaveplan).
- **`.grill/`** — gitignorert, transient arbeidsminne per oppgave (`STATE.md`, `PLAN.md`, `VERIFICATION.md`, `REVIEW.md`, `DECISIONS.md`, `MODELL-STATUS.md`). Durabel verdi graduerer til `docs/`; `.grill/` overlever ikke oppgaven. Mekanikken (når den leses/skrives) eies av agent-fila.

Følg `docs/agents/domain.md` for når `docs/context.md` skal brukes, hvordan
ADR-status tolkes, og når ADR er riktig kilde i kodekommentarer.

## Runtime-verifisering

Dette dokumentet fører ikke levende sjekklister for installert CLI-versjon,
modelltilgjengelighet, deployoppsett eller repository-innstillinger. Verifiser
slike påstander fra gjeldende agentfiler, `copilot skill list`, gateskript,
workflow-filer, `nais/` og GitHub-innstillinger. Ferskt kommando- eller
runtimebevis trumfer historisk statusprosa.

## Proveniens

Kilder, vurderte revisjoner, lisenshåndtering og lokale tilpasninger eies av
`docs/agents/provenance.md`. Oppsettet på disk er den operative kontrakten;
oppstrømskildene er vurderte input, ikke runtime-avhengigheter.
