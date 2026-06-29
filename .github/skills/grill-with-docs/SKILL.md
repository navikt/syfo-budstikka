---
name: grill-with-docs
description: "Bruk når et design, en ny tjeneste eller en ikke-triviell endring skal stresstestes før koding, og du vil ha ADR + glossar som faller ut underveis. @grillmester fase 1–2, eller når noen sier 'grill meg', 'design dette', 'er dette gjennomtenkt'."
---

# grill-with-docs

Et nådeløst design-intervju som skjerper planen OG produserer dokumentasjon (ADR + glossar) underveis. Dette er inngangen til Grillmester sin faseløkke — målet er delt forståelse FØR kode skrives.

## Kontrakt for økta
1. **Ett spørsmål av gangen.** Aldri flere samtidig — det forvirrer.
2. Hvert spørsmål kommer med **din anbefalte svar** og en kort begrunnelse.
3. Gå ned hvert gren av beslutningstreet; løs avhengigheter mellom valg ett for ett.
4. Kan et spørsmål besvares ved å lese kodebasen → **utforsk i stedet for å spørre**.
5. Skriv artefakter **løpende** (ikke til slutt): hver avklart beslutning → ADR-linje; hvert avklart begrep → glossar. Bruk `/domain-modeling` for å holde språket skarpt.

## NAV-seeding (still disse før de generiske)
Velg arketype først, og still domene-spørsmålene som hører til. Den korte versjonen:
- **Arketype:** Backend-API / Hendelseskonsument (Kafka) / Naisjob / Fullstack. (Dette er et Ktor-backend-repo — vekt på de tre første.)
- **Dataklassifisering:** Hvilke data berøres? (åpne / personopplysninger / særlige kategorier / fnr) — styrer auth, logging, lagring og om DPIA trengs.
- **Blind-spots å grave i:** auth (TokenX / Azure AD / ID-porten / Maskinporten), `accessPolicy` mot andre team, idempotens/replay (Kafka), oppførsel når avhengigheter er nede, PII i logger, sletting/oppbevaring, observability fra dag én, feilkontrakt (StatusPages/ApiError).
- **Arkitekturbeslutning?** → utløs ADR via `/nav-architecture-review`.

For **ny tjeneste, ny arketype eller modernisering** — kjør den fulle kravavdekkingen (dybden ligger i references, lastes ved behov):
- [references/nav-arketyper.md](references/nav-arketyper.md) — arketype-valg, domene-spørsmål per personvern/plattform/observerbarhet/team, brownfield endringskonsekvens, modernisering, og hva som oppsummeres i `.grill/CONTEXT.md`.
- [references/blind-spots.md](references/blind-spots.md) — full sjekkliste (auth, DB, Kafka, observerbarhet, sikkerhet) med konsekvens + spørsmål å stille.
- [references/data-classification.md](references/data-classification.md) — NAVs klassifiseringsnivåer, PII-kategorier og arkitekturkonsekvenser.

## ADR-kontrakt
For hver beslutning som er vanskelig å reversere, overraskende uten kontekst, og resultatet av en reell avveining, skriv `.grill/adr/NNNN-<kort-tittel>.md`:
```
# NNNN: <tittel>
- Status: foreslått | besluttet
- Kontekst: <hva tvang frem valget>
- Beslutning: <hva vi valgte>
- Konsekvenser: <hva det betyr, inkl. ulemper>
- Alternativer vurdert: <kort>
```

## Glossar-kontrakt
Hvert domenebegrep som dukker opp avklares i `.grill/GLOSSARY.md` (én linje: `term → presis definisjon i NAV-kontekst`). Konsistent språk er en del av designet, ikke pynt.

## Utfall
Når treet er gjennomgått og bruker har bekreftet: oppsummer beslutningene, pek på ADR-ene og glossaret, og skriv den valgte tilnærmingen til `.grill/CONTEXT.md`. Det er input til plan- og implementeringsfasen.
