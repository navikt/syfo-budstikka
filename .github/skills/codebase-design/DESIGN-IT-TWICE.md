# Design det to ganger

Når du vil utforske alternative grensesnitt for en valgt deepening-kandidat. Basert på «Design It Twice» (Ousterhout) — den første idéen din er sjelden den beste. Bruker vokabularet i [SKILL.md](SKILL.md) — **modul**, **grensesnitt**, **søm**, **adapter**, **gjennomslag**.

**Inline-prinsipp (dette repoet):** designarbeid som krever skjønn skjer i hovedtråden. Du splitter aldri «skriveren» over parallelle agenter — implisitte beslutninger kolliderer. Lag alternativene sekvensielt inline. Read-only utforsking av et alternativ kan offloades til en subagent som returnerer en kort skisse (≤1–2k tegn) når den ellers ville fylt hovedtråden med støy — aldri til å skrive kode.

## Prosess

### 1. Ramm inn problemrommet

Før du skisserer alternativer, skriv en kort forklaring av problemrommet for den valgte kandidaten:

- Hvilke krav et nytt grensesnitt må tilfredsstille (hentes fra `docs/CONTEXT.md` og relevante `docs/adr/`).
- Avhengighetene det vil hvile på, og hvilken kategori de faller i (se [DEEPENING.md](DEEPENING.md)).
- En grov illustrerende kode-skisse for å gjøre kravene konkrete — ikke et forslag, bare en måte å feste kravene.

### 2. Skisser 3+ radikalt ulike grensesnitt

Lag minst tre alternativer, hvert med en distinkt designtvang. Bruk vokabularet fra [SKILL.md](SKILL.md) og domenespråket i `docs/GLOSSARY.md` så ting navngis konsistent.

- Alternativ 1: **Minimer grensesnittet** — sikt på 1–3 inngangspunkter. Maksimer gjennomslag per inngangspunkt.
- Alternativ 2: **Maksimer fleksibilitet** — støtt mange bruksmønstre og utvidelse.
- Alternativ 3: **Optimaliser for den vanligste kalleren** — gjør standardtilfellet trivielt.
- Alternativ 4 (om relevant): **Ports & adapters** rundt avhengigheter som krysser nett (se [DEEPENING.md](DEEPENING.md), kategori 3–4).

For hvert alternativ, skriv:

1. Grensesnitt (typer, metoder/endepunkter, parametre — pluss invarianter, rekkefølge, feilmodi).
2. Bruks-eksempel som viser hvordan en kaller bruker det (kort Kotlin/Ktor).
3. Hva implementasjonen skjuler bak sømmen.
4. Avhengighetsstrategi og adaptere (se [DEEPENING.md](DEEPENING.md)).
5. Avveininger — hvor gjennomslaget er høyt, hvor det er tynt.

### 3. Sammenlign og anbefal

Presenter alternativene sekvensielt så de kan fordøyes ett for ett, og sammenlign dem i prosa. Kontraster på **dybde** (gjennomslag ved grensesnittet), **lokalitet** (hvor endring samler seg) og **søm-plassering**.

Gi til slutt din egen anbefaling: hvilket design som er sterkest og hvorfor. Passer elementer fra ulike alternativer godt sammen, foreslå en hybrid. Vær tydelig — gjesten vil ha et sterkt råd, ikke en meny. Det valgte grensesnittet og begrunnelsen skrives til `docs/CONTEXT.md`; er beslutningen vanskelig å reversere, skriv ADR i `docs/adr/`.
