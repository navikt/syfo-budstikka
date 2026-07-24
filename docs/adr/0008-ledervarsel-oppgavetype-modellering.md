# 0008: Ledervarsel-`oppgavetype` som lukket, budstikka-eid enum

**Dato:** 2026-07-17
**Status:** Godkjent
**Beslutningstakere:** Team eSyfo
**Relatert:** issue #108 (Ledervarsel-producer), ADR 0009 (ledervarsel in-app-only), B1 (domeneblind), B23 (anti-corruption), B30 (merkelapp = lukket enum), B9 (typede value-class-identifikatorer), epic #15

## Kontekst

`LedervarselCreate` skal produseres til konsumenten `navikt/dinesykmeldte-backend` på
`team-esyfo.dinesykmeldte-hendelser-v2` (i dag matet av esyfovarsel). Konsumentens
autoritative kontrakt (verifisert mot kildekode) er:

```
DineSykmeldteHendelse(id, opprettHendelse?, ferdigstillHendelse?)   // JSON, key = id
OpprettHendelse(ansattFnr, orgnummer, oppgavetype, lenke?, tekst?, timestamp, utlopstidspunkt?)
```

`oppgavetype: String` er **påkrevd**, inngår i konsumentens primærnøkkel
`(id, oppgavetype)` (idempotent dedup) og styrer gruppering i Dine Sykmeldte-UI-et
(`OppfolgingsplanerHendelser`/`DialogmoteHendelser`-settene). (Merk: `oppgavetype`
gjelder KUN OPPRETT; `FerdigstillHendelse` keyes på `id`/`reference` alene.)

Vår nøytrale `LedervarselCreate(sykmeldt, orgnummer, text, link?, …)` har **intet**
tilsvarende felt — i motsetning til `ArbeidsgivervarselCreate` som har `tag: Tag`.
Vi må avgjøre hvordan produsenten uttrykker `oppgavetype` uten å bryte
abstraksjonen (B23) eller domeneblindheten (B1).

**Kjernespenningen** er mellom to prinsipper:
- **B23 (anti-corruption):** «nedstrøms-felt lekker aldri inn i offentlig kontrakt».
  En opak `oppgavetype`-streng hvis gyldige verdier ER dinesykmeldtes vokabular
  tvinger produsentene til å kjenne dinesykmeldtes API i tillegg til budstikkas →
  abstraksjonen lekker.
- **B1 (domeneblind):** budstikka skal ikke bære domenekunnskap. `oppgavetype`-
  verdiene ligner domenehendelse-katalogen → å enumerere dem nærmer budstikka domenet.

Hvorfor nå: #108 kan ikke sende en gyldig `OpprettHendelse` uten å ha bestemt hvor
`oppgavetype` kommer fra. Beslutningen blokkerer produsent-snittet.

## Beslutning

Vi modellerer `oppgavetype` som en **lukket, typet enum** eid av budstikka i
kontraktbiblioteket (samme mønster som `Tag`/`AltinnResourceId`, B30/B32), og mapper
hver verdi til dinesykmeldte-wirens `String` i anti-corruption-laget (B23):

```kotlin
enum class Oppgavetype(val wireValue: String) {   // (i): budstikka-eide case-navn, wireValue mapper til dinesykmeldte-streng
    DIALOGMOTE_INNKALLING("DIALOGMOTE_INNKALLING"),
    // … utvides additivt per oppgavetype som migreres gjennom LEDERVARSEL
}

data class LedervarselCreate(
    val sykmeldt: PersonIdentifier,
    val orgnummer: Orgnummer,
    val oppgavetype: Oppgavetype,   // påkrevd (konsumentens PK-krav)
    val text: String,
    // … eksisterende felt uendret
)
```

Budstikka **forgrener aldri** på `oppgavetype` (`when(oppgavetype)` finnes ikke, jf.
B30/B39) — enumen er en NAVNELISTE, ikke oppførsel; den mappes kun til wire via
`wireValue`. Domeneblindheten (B1) gjelder oppførsel, ikke at navnet finnes i
kontraktlib.

**Navngivning (valgt: i):** case-navnene er budstikkas egne domeneord, og hver bærer
en eksplisitt `wireValue` = dinesykmeldtes eksakte streng → wire-verdien er frikoblet
fra Kotlin-identifikatoren (dinesykmeldte kan endres uten å røre produsentene).

**Scope for #108:** leverer maskineriet + ÉN representativ verdi
(`DIALOGMOTE_INNKALLING`, wire `"DIALOGMOTE_INNKALLING"`). Resten av settet
(`DIALOGMOTE_AVLYSNING`, `DIALOGMOTE_ENDRING`, `DIALOGMOTE_REFERAT`,
`DIALOGMOTE_SVAR_BEHOV`, `OPPFOLGINGSPLAN_TIL_GODKJENNING`,
`OPPFOLGINGSPLAN_PAAMINNELSE`) fylles additivt ved onboarding av hver produsent
(B36-sekvensering).

**Avgrensning:** budstikka enumererer KUN de oppgavetypene som faktisk migreres
gjennom LEDERVARSEL-kanalen — ikke hele esyfovarsels `DineSykmeldteHendelseType`-
katalog på forhånd.

## Alternativer vurdert

### Alternativ A: Lukket, budstikka-eid enum (valgt)

**Beskrivelse:** budstikka enumererer de gyldige `oppgavetype`-verdiene i kontraktlib,
som for `Tag`/`AltinnResourceId`, og mapper til wire.

**Fordeler:**
- **Abstraksjonsintegritet (B23):** produsenten forholder seg KUN til budstikkas
  typer — trenger aldri kjenne dinesykmeldtes API. Ett koblingspunkt, ikke to.
- **Konsistens:** hvert eksisterende kategoriserings-felt i kontrakten er allerede en
  budstikka-eid enum (`Tag`, `AltinnResourceId`, `Varseltype`,
  `ArbeidsgiverMeldingstype`, `SendingWindow`, `DistributionType`).
- **Produsent-DX:** kompileringsvalg + autocomplete + tidlig feil ved byggetid via
  delt lib (Kafka er async → ingen synkron innsendingsvalidering finnes uansett).
- **Isolerer nedstrøms-endring:** dinesykmeldte kan endre en wire-verdi → budstikka
  oppdaterer MAPPINGEN ett sted; produsentene røres ikke.

**Ulemper (tatt med åpne øyne):**
- Budstikka i release-stien for hver ny oppgavetype som migreres → onboarding-skatt.
  Bundet ved additiv utvidelse + kun LEDERVARSEL-verdier. Samme SLAGS skatt B30
  allerede aksepterte for merkelapp (grad, ikke art).
- Kontraktlib navngir domenenære verdier. Akseptabelt per B30 (navngi ≠ forgrene).

### Alternativ B: Åpen, typet value class (`value class Oppgavetype(String)`)

**Beskrivelse:** budstikka eier en value class rundt en fri streng; produsenten setter
verdi; opak mapping til wire.

**Fordeler:**
- Ingen onboarding-skatt; produsent kan bruke ny verdi uten budstikka-release.
- Typet grense (B9-idiom); enumererer ikke domenekatalogen.

**Ulemper:**
- **Bryter B23:** de gyldige verdiene ER dinesykmeldtes vokabular → produsenten må
  kjenne dinesykmeldtes API i tillegg til budstikkas. Abstraksjonen lekker — nettopp
  det budstikka finnes for å hindre. Diskvalifiserende: art-forskjell, ikke grad.
- Ingen byggetids-DX; typo fanges først hos konsumenten, spredt over N produsenter.

### Alternativ C: Naken `String` på kontrakten

**Beskrivelse:** `val oppgavetype: String` direkte.

**Ulemper:** som B, pluss «stringly typed» — mister typegrensen og bryter husets
value-class-idiom (B9). Verste av tre.

### Alternativ D: Gjøre ingenting

**Beskrivelse:** utsett #108 til feltet er avklart som eget spor.

**Ulemper:** blokkerer det første ledervarsel-snittet unødvendig — beslutningen er
liten, intern og additiv.

## NAV-spesifikke vurderinger

### Sikkerhet og personvern
- **Dataklassifisering:** `oppgavetype` selv er **ikke** PII (teknisk kategori).
  Selve `OpprettHendelse` bærer `ansattFnr` (Fortrolig, art. 9-nær, B42) — uendret av
  *denne* beslutningen; håndteres i #108s producer-wiring (fnr aldri i logg B46;
  maskering B9).
- **Auth-mekanisme:** uendret av denne beslutningen (producer-auth/ACL avgjøres i #108).
- **PII-håndtering:** `oppgavetype` er trygt i logg (`kv("oppgavetype", …)`).
- **Personvern/DPIA:** ingen ny personopplysningskategori → ingen ny DPIA utløst.

### Plattform (NAIS/GCP)
- Ingen manifest-/ressurs-/observability-endring følger av *modelleringsvalget*.
  Topic-ACL + `accessPolicy` for selve produseringen hører til #108.

### Team og organisasjon
- **Berørte team:** budstikkas produsent-apper (velger enum-verdi). Wire-en er
  uendret for dinesykmeldte (`String` uansett) → ingen dinesykmeldte-koordinering
  utløst av modelleringsvalget.
- **Architecture Advice:** intern kontraktlib-beslutning; enum-settet koordineres med
  produsent-teamene ved onboarding (samme flyt som merkelapp, B30).
- **Migrasjon/rollback:** additiv (nytt felt + enum i kontraktlib). Lav innlåsing.

## Konsekvenser

### Positive
- Abstraksjonen holder: produsenter kobler kun mot budstikka, ikke dinesykmeldte.
- Byggetids-typesikkerhet for produsentene; nedstrøms-endring isoleres i mappingen.
- Konsistent med hele den eksisterende kontraktmodellen.

### Negative
- Budstikka må legge til enum-verdi (release) når en ny oppgavetype migreres gjennom
  LEDERVARSEL. Bundet: kun migrerte verdier, additivt.

### Risiko

| Risiko | Sannsynlighet | Konsekvens | Mitigering |
|--------|--------------|------------|-----------|
| Ny oppgavetype trengs før budstikka har den i enum | Middels | Produsent blokkeres til budstikka-release | Additiv utvidelse er lav-friksjon; ny oppgavetype krever dinesykmeldte-release uansett → koordinert release er allerede normalen |
| Enum-verdi ≠ dinesykmeldtes forventede streng (mapping-feil) | Lav | Varsel vises feil/ikke i Dine Sykmeldte | `wireValue` speiler dinesykmeldtes streng eksakt; e2e-test mot kjent verdi |

## Aksjonspunkter

- [x] Navngivning avklart: (i) budstikka-eide case-navn + `wireValue`-mapping; representativ verdi `DIALOGMOTE_INNKALLING`, resten ved onboarding
- [ ] Legg til `Oppgavetype`-enum + `oppgavetype`-felt i `LedervarselCreate` (kontraktlib) + oppdater `docs/kontrakt.md` — #108
- [x] Fest beslutningen som B61 i `docs/context.md` — @grillmester
- [ ] Bygg producer-adapter (`Oppgavetype.wireValue` → wire-`String`, key = `reference`) — #108
- [ ] E2E-test som asserterer `OpprettHendelse.oppgavetype` mot kjent verdi — #108
- [ ] Fastsett startsett av oppgavetyper fra migreringsscope (B36-sekvensering) — Team eSyfo
