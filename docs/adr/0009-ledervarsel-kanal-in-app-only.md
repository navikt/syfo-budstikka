# 0009: Ledervarsel-kanal er in-app-only — drop `externalVarsling`, korriger B25

**Dato:** 2026-07-17
**Status:** Godkjent
**Beslutningstakere:** Team eSyfo
**Relatert:** issue #108 (Ledervarsel-producer), #24 (Arbeidsgivervarsel), ADR 0008 (oppgavetype), B6 (kanal pr. hendelse), B23 (anti-corruption), B24 (NL-resolusjon), B25 (sendevindu), B32 (AG-mottaker inkl. NarmesteLeder), B40 (falsk affordance), epic #15

## Kontekst

Ved kartlegging av dinesykmeldte-kontrakten for #108 sporet vi esyfovarsels faktiske
NL-flyt (`DialogmoteInnkallingNarmesteLederVarselService`). For ETT ledervarsel kaller
esyfovarsel to helt separate nedstrøms-kanaler:

1. `sendVarselTilDineSykmeldte(...)` → `DineSykmeldteVarsel` på
   `team-esyfo.dinesykmeldte-hendelser-v2` = **in-app aktivitetsvarsel** i Dine
   Sykmeldte-oversikten. `dinesykmeldte-backend.handleHendelse` gjør kun et DB-insert;
   **ingen** ekstern push (SMS/e-post).
2. `createNewSak` + `createNewKalenderavtale` + `sendTilArbeidsgiverNotifikasjon` mot
   **notifikasjon-produsent-api (fager)** med `ledersEpost`/`epostHtmlBody`,
   `merkelapp=DIALOGMOTE`, `narmesteLederFnr` = **e-post til lederen**.

Konklusjon: den eksterne varslingen til lederen kommer fra **arbeidsgivernotifikasjon
(fager)** — budstikkas ARBEIDSGIVERVARSEL-kanal med `NarmesteLeder`-mottaker (B32,
#24) — **ikke** fra Dine Sykmeldte-kanalen.

Men budstikkas `LedervarselCreate` bærer i dag `externalVarsling: ExternalVarsling?`
(og `sendingWindow`), og B25 lister «ledervarsel m/ekstern» som *eksternbærende* med
default `NKS_AAPNINGSTID`. Begge bygger på en feil antakelse: LEDERVARSEL-kanalen har
ingen ekstern bærer. Et felt konsumenten ikke kan påvirke er en falsk affordance
(B40: «et felt som ignoreres er verre enn fravær»).

Hvorfor nå: #108 definerer LEDERVARSEL-kanalens kontraktform. Feltet må avklares før
kontrakten publiseres til produsenter.

## Beslutning

1. **LEDERVARSEL-kanalen er rent in-app** (Dine Sykmeldte aktivitetsvarsel). Ekstern
   varsling til nærmeste leder skjer via en egen **ARBEIDSGIVERVARSEL**-dispatch med
   `NarmesteLeder(sykmeldt)`-mottaker (B6: «flere kanaler → flere hendelser»; B32).
2. **Fjern `externalVarsling` fra `LedervarselCreate`** (v1). Feltet har ingen bærer
   på denne kanalen.
3. **Behold `sendingWindow`** på `LedervarselCreate` (budstikka kan gate når in-app-
   hendelsen blir synlig), men **default flippes til `ONGOING`** (LØPENDE) — som
   microfrontend/ren in-app.
4. **Korriger B25:** «ledervarsel m/ekstern» strykes fra listen over eksternbærende
   leveranser. Festes som ny beslutning (B62) i `docs/context.md`.

## Alternativer vurdert

### Alternativ A: In-app-only + drop `externalVarsling` (valgt)

**Fordeler:**
- Ingen falsk affordance (B40); kontrakten speiler hva kanalen faktisk kan.
- Domeneblindt og konsistent med B6 (én kanal pr. dispatch) og B27 (én handler pr.
  kanal): ekstern-til-leder er ARBEIDSGIVERVARSEL, ikke en skjult bi-effekt av
  LEDERVARSEL.
- Speiler esyfovarsels faktiske to-kanals-flyt uten å skjule den.

**Ulemper:**
- En konsument som vil ha både in-app OG e-post til lederen må sende to dispatcher.
  Akseptabelt — det er nettopp B6-modellen, og speiler dagens virkelighet (to kall).

### Alternativ B: Behold `externalVarsling`, la LEDERVARSEL også produsere arbeidsgivernotifikasjon

**Beskrivelse:** én LEDERVARSEL-dispatch fører til både DineSykmeldte-hendelse OG
fager-kall internt i handleren.

**Ulemper:** slår to kanaler sammen i én handler → bryter B6/B27 (én kanal, én
handler, én idempotent side-effekt pr. rad). Gjenskaper esyfovarsels sammenfiltrede
orkestrering som budstikka skal vekk fra. Forkastet.

### Alternativ C: Behold `externalVarsling` som inert felt «for framtiden»

**Ulemper:** falsk affordance (B40) — et felt produsenten setter men som ignoreres.
Legges til non-breaking senere hvis en ekte ekstern-ledervarsel-bærer dukker opp.
Forkastet.

### Alternativ D: Gjøre ingenting

**Beskrivelse:** behold `LedervarselCreate` med `externalVarsling` + B25 uendret.

**Ulemper:** publiserer en falsk affordance og en feilaktig eksternbærende-antakelse
til produsentene. Forkastet.

## NAV-spesifikke vurderinger

### Sikkerhet og personvern
- **Dataklassifisering:** reduserer PII-flate — `externalVarsling` (SMS/e-post-tekst)
  fjernes fra ledervarsel-kontrakten. E-post til leder (m/ `narmesteLederFnr`,
  `ledersEpost`) håndteres i ARBEIDSGIVERVARSEL-kanalen (#24), ikke her.
- **Auth/accessPolicy:** uendret av denne beslutningen (dinesykmeldte-topic-ACL i #108).
- **PII i logg:** uendret (B46 gjelder).

### Plattform (NAIS/GCP)
- Ingen manifest-/ressursendring følger av kontraktformen.

### Team og organisasjon
- **Berørte team:** budstikkas produsent-apper. En produsent som forventet ekstern-
  til-leder via ledervarsel må i stedet sende ARBEIDSGIVERVARSEL med NarmesteLeder-
  mottaker. Dokumenteres i `docs/kontrakt.md`.
- **Migrasjon/rollback:** v1-kontrakten er ikke i produksjon → fjerning av felt er
  ikke breaking mot live-konsumenter. Additiv å legge til igjen senere ved reelt behov.

## Konsekvenser

### Positive
- Kontrakten er ærlig om kanalens evner; ingen falsk affordance.
- Klar separasjon: LEDERVARSEL = in-app, ARBEIDSGIVERVARSEL(NL) = ekstern til leder.
- B25 korrigert før den forplanter seg til sendevindu-default-koden.

### Negative
- Konsument må sende to dispatcher for in-app + e-post til leder (bevisst, B6).

### Risiko

| Risiko | Sannsynlighet | Konsekvens | Mitigering |
|--------|--------------|------------|-----------|
| Produsent antar ledervarsel også e-poster lederen | Middels | Leder får kun in-app, forventer e-post | Dokumenter i kontrakt at ekstern-til-leder = ARBEIDSGIVERVARSEL(NL); ingen `externalVarsling`-felt å feiltolke |
| Reelt behov for ekstern ledervarsel-bærer dukker opp senere | Lav | Kontraktendring | Additivt (non-breaking) å legge feltet til igjen med en faktisk bærer |

## Aksjonspunkter

- [ ] Fjern `externalVarsling` fra `LedervarselCreate`; flipp `sendingWindow`-default til `ONGOING` for ledervarsel — #108
- [ ] Oppdater `docs/kontrakt.md` (ledervarsel-seksjon: in-app-only; ekstern-til-leder = ARBEIDSGIVERVARSEL(NL)) — #108
- [x] Fest B62 (korrigerer B25) i `docs/context.md` — @grillmester
- [ ] E2E: ledervarsel-dispatch → `OpprettHendelse` på dinesykmeldte-topic, ingen ekstern side-effekt — #108
- [ ] Informer produsent-team om to-kanals-mønsteret ved onboarding — Team eSyfo
