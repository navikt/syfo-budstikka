# Grillmester — Copilot-agentoppsett for syfo-budstikka

Dette dokumentet forklarer repoets GitHub Copilot-oppsett (`.agent.md` /
`.instructions.md` / `SKILL.md`). Det er menneskevendt navigasjon og rasjonale;
den operative konfigurasjonen ligger i filene dokumentet peker til. Ikke
dupliser levende status eller detaljer hit.

## Hva oppsettet er (pekere)
- **Agenter** — `.github/agents/`: `@grillmester` er orkestrator og inline implementør; `grill-inspektor` er en opt-in, fersk read-only reviewer. Agentfilene eier roller og arbeidsflyt.
- **Skills** — `.github/skills/`: design/utforsking, implementering/kvalitet, backend-domene (Ktor/NAV) og tverrgående flyt. De auto-oppdages på `description`-feltet; ingen katalog eller opptelling gjentas her — katalogen på disk er fasiten.
- **Instruksjoner** — `.github/instructions/`: always-on og stiavgrensede kontrakter. Filene og deres frontmatter eier den gjeldende inndelingen.
- **Modellpin-gate** — `scripts/validate-agent-models.sh` validerer deklarerte modellpinner mot repoets allowlist i CI.

## Designprinsipper (hvorfor det er bygd slik)
1. **Skriveren er inline** — koding parallelliseres ikke fordi implisitte beslutninger kolliderer.
2. **Subagenter er kontekstverktøy**, kun til read-only utforsking, fersk inspektørreview og opt-in divergent designutforsking. Aldri parallell skriving av kode.
3. **Kvalitetsgater er deterministiske og utenfor modellen** — `./gradlew test`, lint, build og `scripts/validate-agent-models.sh` gir positivt bevis.
4. **Disk er minne** (`.grill/`), ikke samtalen.

## Durable vs transient: `docs/` og `.grill/`
- **`docs/`** — committet, discoverable: `docs/adr/NNNN-*.md` (statusmerkede beslutninger; tolking og livsløp eies av `docs/agents/domain.md`), `docs/glossary.md` (domenespråk), `docs/context.md` (orientering og overordnet status, ikke krav eller oppgaveplan).
- **`.grill/`** — gitignorert, transient arbeidsminne per oppgave (`STATE.md`, `PLAN.md`, `VERIFICATION.md`, `REVIEW.md`, `DECISIONS.md`). Durabel verdi graduerer til `docs/`; `.grill/` overlever ikke oppgaven. Mekanikken (når den leses/skrives) eies av agent-fila.

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
