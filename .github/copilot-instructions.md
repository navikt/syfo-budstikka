# Copilot-instruksjoner — syfo-budstikka

Ktor-backend (Kotlin, NAV / `no.nav.syfo`). Java 25, Gradle, Netty. Norsk er arbeidsspråk.

## Agent-oppsett
- **@grillmester** (Opus 4.8) er orkestrator + inline implementør for ikke-triviell jobb. Den kjører en faseløkke (grill → design → plan → implementer → verifiser → server) og skriver arbeidsminne til `.grill/`.
- **grill-inspektor** (GPT-5.5, internt) er fersk kryssmodell-reviewer — **opt-in**, anbefalt-på for høyrisiko. Det er her «begge modellfamilier ser på arbeidet» bevares, men kostnadskontrollert.
- Ingen svake modell-tier i oppsettet. Kvalitet kommer fra sterk modell + deterministiske gater, ikke fra billige mellomledd.

## Faste prinsipper (gjelder all kode-assistanse i repoet)
- **Kvalitetsgater er deterministiske og utenfor modellen:** `./gradlew test`, lint og build avgjør pass/fail. Ingen «ser riktig ut»-påstander uten ferskt bevis (kommando + output + exit-kode i samme melding).
- **Inline skriving:** koding som krever skjønn gjøres i hovedtråden. Subagenter brukes kun til read-only utforsking, kryssmodell-verify og opt-in divergent design-utforsking (design-it-twice).
- **Skills kalles eksplisitt** med `/skill-navn` når en oppgave berører et domene som har skill (se `.github/skills/`). Ikke stol på at de oppdages automatisk.
- **Disk-som-minne:** lengre arbeid sporer beslutninger/plan/verifikasjon i `.grill/` (`STATE.md` leses først). Ved ~55 % vindu-okkupasjon: checkpoint + fersk tråd.

## Modell-policy
Roller er pinnet i agentfilene og validert deterministisk av `scripts/validate-agent-models.sh` (hardt fail + skriver `.grill/MODELL-STATUS.md`). En modell påstår aldri selv hvilken modell den er.

## Hvor ting ligger
- Agenter: `.github/agents/`  ·  Skills: `.github/skills/`  ·  Instruksjoner per filtype: `.github/instructions/`
- Designoversikt: `.github/GRILLMESTER.md`
