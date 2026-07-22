# Migrering: esyfovarsel → syfo-budstikka

Hvordan vi flytter varsel-produksjon fra `navikt/esyfovarsel` til syfo-budstikka uten
dobbeltvarsling, tapte lukkinger eller spøkelses-data. Beslutninger er festet som B34–B37
i `context.md`. Grunnlaget er en kildekode-kartlegging av esyfovarsel (2026-07, HEAD
`3ac54de`), se §«Type-inventar».

## Hovedutfordring

Varsler har **levetid**. Å lage nye varsler i budstikka er lett; det vanskelige er
in-flight varsler som straddler cutoveren: den nedstrøms notifikasjonens identitet
(`eksternReferanse`/`grupperingsid`) ble minted av esyfovarsel og finnes ofte *kun* i
esyfovarsels DB (`UUID.randomUUID()`). budstikka kan derfor ikke lukke dem.

**Tre alternativer forkastet:**
- **Fasade** (budstikka videresender closes til esyfovarsels `varselbus`): unødvendig for
  selv-utløpende typer, og *utilstrekkelig* for dialogmøte (delt SAK med tilfeldig
  grupperingsid kan ikke overtas midt i).
- **Referanse-kontinuitet** (budstikka utleder samme referanse): umulig — referansene er
  stort sett `UUID.randomUUID()`; kobler dessuten budstikka til legacy-skjema.
- **State-handover** (importer esyfovarsels åpne varsler): kompleks, risikabel referanse-
  avstemming.

## Ryggrad (B34): klebrig eierskap, per-prosess produsent-rutet

Systemet som gjorde **OPPRETT eier hele livsløpet**. Produsenten ruter FERDIGSTILL og
oppfølgings-hendelser til *samme* system. Migreringsenheten er en **prosess/gruppering**
(en hel dialogmøte-sak, én møtebehov-runde, ett brev) — ikke ett enkeltvarsel.

- **Alt nytt → ny løsning:** produsenten flipper sin *egen* OPPRETT-output til budstikka.
- **Anti-dobbeltvarsling:** hver prosess sendes til nøyaktig ett system (ingen dobbel-send;
  systemene deler ikke dedup-lager).
- **Ingen race:** OPPRETT og FERDIGSTILL deler partisjonsnøkkel (mottaker, B5/B24/B32) →
  ordnet på samme partisjon, så en close kan aldri prosesseres før sin egen OPPRETT.

## Hybrid-strategi (B35): del etter varselets natur

| Natur | Strategi | Close-maskineri |
|---|---|---|
| Selv-utløpende / informativ / fire-and-forget | **Hard switch per type + la utløpe** | Ingen |
| Ekte oppgave/sak uten tidscap + tilfeldig referanse | **Prosess-rutet drain-close** (kolonne, B37) | Ja |
| Grensetilfelle (oppfølgingsplan, AG-sak ~4 uker) | Hard switch + godta ~4-ukers stale-vindu | Nei (bevisst forenkling) |

**Hvorfor dialogmøte MÅ ha prosess-ruting** — ikke pga. utløpstid (microfrontend utløper på
møtedato, AG-sak på `hardDeleteDate` +4 mnd), men fordi dialogmøte er en **tilstandsmaskin**
over hendelser som deler én sak (INNKALT → NYTT_TID_STED → AVLYST/REFERAT). En oppfølging
etter cutover som havner i budstikka kan ikke oppdatere esyfovarsels sak → **spøkelses-møte**:
et flyttet/avlyst møte vises fortsatt som aktivt til det utløper. Å tvangslukke det gamle
tidlig fjerner påminnelsen *før* møtet — også en regresjon. Derfor: hele dialogmøtet blir hos
esyfovarsel til det er avsluttet; kun *nye* dialogmøter starter i budstikka.

## Hvilke apper trenger kolonnen (B37)

Kriteriet: **event-drevet lukking uten tidsutløp + tilfeldig referanse.** Kun 2 av 12
produsenter treffer det klart; 2 grensetilfeller godtar stale-vindu; 8 trenger ingenting.

| Produsent | Type(r) | Lukking | Kolonne? |
|---|---|---|---|
| **isdialogmote** | dialogmøte-familien (SAK + INNKALT-OPPGAVE + microfrontend + AG-sak) | event-drevet, delt sak, random UUID | **JA (tung — flagg på saken)** |
| **syfomotebehov** | SM/NL_DIALOGMOTE_SVAR_MOTEBEHOV (OPPGAVE) | event-drevet, random UUID, ingen cap | **JA (lett — flagg på møtebehov-runden)** |
| isoppfolgingsplan | NL_OPPFOLGINGSPLAN_FORESPORSEL (AG-sak, 4 uker) | close, bundet 4 uker | Nei — godtar stale |
| ~~syfooppfolgingsplanservice~~ | NL/SM_OPPFOLGINGSPLAN_SENDT_TIL_GODKJENNING | **DEPRECATED — skrus av etter sommeren** | Utenfor scope (migreres ikke) |
| aktivitetskrav-backend | SM_AKTIVITETSPLIKT | auto-lukke-jobb 2–14d, *deterministisk* UUID | Nei — selv-lukker |
| meroppfolging-backend | SM_MER_VEILEDNING | tidsutløp 105d | Nei |
| ismeroppfolging | SM_KARTLEGGINGSSPORSMAL | tidsutløp 30d | Nei |
| isarbeidsuforhet / isfrisktilarbeid / ismanglendemedvirkning | BREV-typene | fire-and-forget | Nei |
| syfo-oppfolgingsplan-backend | SM_OPPFOLGINGSPLAN_OPPRETTET | BESKJED, ingen close | Nei |
| syfo-dokumentporten | AG_VARSEL_ALTINN_RESSURS | ferdigstill *ignoreres*, 4mo TTL | Nei |

Merk: OPPGAVEr *med* tidscap (mer_veiledning, kartlegging, aktivitetsplikt) lukker seg selv →
ingen kolonne. Microfrontend selv-utløper på `synligTom`; kun dialogmøtes microfrontend er
floket, og det dekkes av at isdialogmote ruter hele saken.

## Kolonne-mekanismen (B37)

Flagget `varselsystem ∈ {ESYFOVARSEL, BUDSTIKKA}` bor i **produsentens egen prosess-tabell**
(ikke i budstikka), settes ved OPPRETT, leses ved FERDIGSTILL.
- **isdialogmote:** flagget henger på **dialogmøtet/saken**, ikke enkeltvarselet — så hele
  oppfølgingskjeden rutes konsistent til samme system.
- **syfomotebehov:** flagget på **møtebehov-runden**.
- Foretrekkes fremfor ren dato-regel fordi det overlever rollback (en prosess startet i
  budstikka-vinduet forblir merket BUDSTIKKA selv om typen midlertidig rulles tilbake).

### Konkret eksempel: syfomotebehov

Engangsendring: (1) produser budstikkas kontrakt (B22) til `team-esyfo.budstikka.v1`;
(2) `varselsystem`-flagg på møtebehov-runden, satt ved OPPRETT; (3) rut FERDIGSTILL etter
flagget; (4) behold gammel `varselbus`-output til gamle runder er drenert.

| Etter cutover T | Ruting | Resultat |
|---|---|---|
| Nytt møtebehov B (OPPRETT) | → budstikka (`BUDSTIKKA`) | leveranse(r), fan-out til alle kanaler (B13/B14) |
| Bruker svarer på B (FERDIGSTILL) | flagg=BUDSTIKKA → budstikka | Inaktiver lukker alle kanaler på `referanse` (B19–B21) |
| Bruker svarer på A (før-T, FERDIGSTILL) | flagg=ESYFOVARSEL → esyfovarsel (`varselbus`, `ferdigstill=true`) | esyfovarsel slår opp egen random-UUID, lukker |

## Sekvensering (B36): per (type × produsent), aldri big-bang

Big-bang konsentrerer risiko til én dag. Bevis pipelinen på lavrisiko-typer først.

| Trinn | Typer / produsenter | Hvorfor |
|---|---|---|
| **1** | BREV-typene: isarbeidsuforhet, ismanglendemedvirkning, isfrisktilarbeid | Fire-and-forget, 1 kanal, null straddle, deterministisk `journalpost.uuid`. Beviser inbox→beslutning→outbox→dokdist. |
| 2 | BESKJED uten close: syfo-oppfolgingsplan-backend, `*_TILBAKEMELDING` | Ingen lukking |
| 3 | Tidsbaserte fler-kanal: meroppfolging-backend, ismeroppfolging | Selv-utløp (30–105d) |
| 4 | aktivitetskrav-backend | Auto-lukke-jobb budstikka må overta |
| **5 (sist)** | Dialogmøte-familien + AG-Altinn: **isdialogmote** (+ syfomotebehov, syfo-dokumentporten) | Delt SAK-tilstandsmaskin; migreres på sak-grense, aldri midt i. Den store jobben. |

## Drain + dekommisjon

Per type: når produsenten har null in-flight *før-cutover*-prosesser → fjern gammel output,
esyfovarsel slutter å håndtere typen. Drain-vindu:
- Fire-and-forget: momentant.
- Tidsbaserte: naturlig utløp (30d–15 uker).
- Dialogmøte: bundet av **møtedatoene** (typisk uker); 4-mnd `hardDelete` kun ytre
  sikkerhetsnett for saker som aldri ble avsluttet.

esyfovarsel kan dekommisjoneres helt når *alle* typer har null in-flight.

## Type-inventar (kilde: navikt/esyfovarsel)

`HendelseType` (25 typer) i `EsyfovarselHendelse.kt:88-115`; prefiks `SM_`/`NL_`/`AG_` =
sykmeldt/nærmeste leder/arbeidsgiver. Innkommende topic: `team-esyfo.varselbus`. Kanaler:
`min-side.aapen-brukervarsel-v1` (tms), `team-esyfo.dinesykmeldte-hendelser-v2`,
`flex.ditt-sykefravaer-melding`, notifikasjon-produsent-api (GraphQL), dokdist (REST),
`min-side.aapen-microfrontend-v1`. Referanser: BREV/AG-Altinn bruker deterministisk
`journalpost.uuid`/`eksternReferanseId` fra produsent; resten `UUID.randomUUID()` (kun i
esyfovarsels DB). Ingen aktiv fremtidsdatert scheduler-kø (`PlanlagtVarsel` er legacy).

## Åpne punkter

- **syfooppfolgingsplanservice er deprecated** og skrus av etter sommeren (2026) → utenfor
  migreringsscope. AVKLART: funksjonen forsvinner **helt** (flyttes ikke) → budstikka bygger
  aldri `*_OPPFOLGINGSPLAN_SENDT_TIL_GODKJENNING`; disse 2 typene dør med appen.
- **isdialogmote sak-grense:** eksakt hvordan flagget henger på dialogmøtet/saken og hvordan
  hele oppfølgingskjeden (INNKALT/NYTT_TID_STED/AVLYST/REFERAT) rutes konsistent — detaljeres
  når vi når trinn 5.
- **aktivitetsplikt auto-lukke-jobb:** budstikka må reprodusere jobbens 2–14-dagers auto-lukke
  for varsler *den* lager (esyfovarsels jobb dreneres parallelt).
- **Microfrontend-utløp:** budstikka eier egen `synligTom`-basert lukking (jf. esyfovarsels
  `closeExpiredMicrofrontendsJob`).
