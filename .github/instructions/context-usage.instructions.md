---
description: "Brukes når arbeid i repoet refererer til docs/context.md; definerer når context skal brukes, og når ADR/glossar/kode er riktig kilde i stedet."
applyTo: "**"
---

# Bruk av `docs/context.md`

`docs/context.md` er arbeidskontekst for valgt retning, ikke universell sannhetskilde.

## Bruk `docs/context.md` når

- du orienterer deg i grill-, design- og planfaser
- du trenger status på åpne/låste B-beslutninger
- du trenger pekere til relevante temadokumenter

## Ikke bruk `docs/context.md` når

- du skriver kodekommentarer som ikke trenger designhistorikk
- du skriver API-feilmeldinger eller runtime-logger
- du trenger bindende beslutning (bruk ADR i stedet)

## Kildeprioritet

1. `docs/adr/NNNN-*.md` for bindende, vanskelig-reversible beslutninger
2. `docs/glossary.md` for domenebegreper
3. `docs/context.md` for valgt tilnærming og status
4. `.grill/*` for transient arbeidsminne i pågående arbeid

## Referansestil

- Skriv filsti som `docs/context.md` i prosa.
- Ikke skriv `@docs/context.md` i kodekommentarer.
- ADR-referanse i kodekommentar er OK når den forklarer en ikke-triviell avveining.
