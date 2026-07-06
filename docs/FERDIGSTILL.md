# FERDIGSTILL-flyt — syfo-budstikka

Hvordan budstikka lukker/inaktiverer et tidligere sendt varsel, uten domenekunnskap.
Avledet av B3, B4, B6 og B19–B21.

## Prinsipp
FERDIGSTILL er en **egen hendelse** på samme kontrakt og topic som OPPRETT, og går
gjennom **samme flyt og samme outbox-maskineri**. En lukking er bare en `leveranse`-rad
med `operasjon=INAKTIVER` — den plukkes, retryes (backoff/frist) og er idempotent
(egen `leveranse.id`) på lik linje med en utsending.

## Targeting (B19, B38)
- FERDIGSTILL er **typet pr. lukkbar kanal** (B38): kanal er implisitt i typen
  (`BrukervarselInaktiver`, `LedervarselInaktiver`, `DittSykefravaerInaktiver`,
  `ArbeidsgivervarselInaktiver`). Hendelsen er thin: `referanse` + typet **nøkkel**.
- Nøkkelen er typet (`Personident`/`Orgnummer`) → bevarer PII-maskering (B9) og gjør
  ulovlige `(kanal, nøkkel)`-par urepresenterbare.
- Matching: budstikka slår opp åpen OPPRETT-leveranse på `(referanse, mottaker_id, kanal)`,
  der `mottaker_id` = matchnøkkelen = **OPPRETTs partisjonsanker** (det konsumenten kjenner):
  sykmeldt-fnr for BRUKERVARSEL/LEDERVARSEL/DITT_SYKEFRAVAER (B24: sykmeldt, ikke NL-fnr),
  orgnr for ARBEIDSGIVERVARSEL. Resolvert NL-fnr / `ekstern_respons_id` bor i payload/egne
  kolonner og deltar ALDRI i matching.
- Vil konsument lukke flere kanaler → sender én FERDIGSTILL pr. kanal. Budstikka
  bestemmer aldri scope/fan-out selv → forblir domeneblind.

## Lukkeoperasjon avledes fra lagret rad (B39)
FERDIGSTILL-hendelsen bærer ALDRI meldingstype/sti/operasjon. Beslutnings-workeren (B28)
finner matchende OPPRETT-leveranse, `decide()` fryser lukkeparametrene (`meldingstype`, sti
NL/Altinn, `ekstern_respons_id`, `grupperingsid`) onto INAKTIVER-leveransen, og `Kanalhandler`
(B27) dispatcher på disse **lagrede tekniske attributtene** — aldri på domenetype (B1/B30).
Kompatibelt med klebrig eierskap (B34): budstikka mottar kun FERDIGSTILL for OPPRETT den selv
laget → lagret rad finnes alltid.

## Edge-håndtering (B20)
| Situasjon | Handling |
| --- | --- |
| OPPRETT funnet, `SENDT` | Normal: skriv `leveranse(operasjon=INAKTIVER)` → outbox lukker på kanalen |
| OPPRETT funnet, fortsatt `KLAR` (ikke sendt) | **Kanseller lokalt**: sett OPPRETT-leveranse → `KANSELLERT` (terminal). Ingen utsending + umiddelbar lukking. Mulig fordi OPPRETT/FERDIGSTILL deler partisjon (B5) og prosesseres i rekkefølge |
| Ingen matchende OPPRETT | Ikke hard feil: inbox → `BEHANDLET`, ingen leveranse, logg + metrikk `ferdigstill_uten_treff`. (Partisjonsordning gjør «OPPRETT kommer senere» usannsynlig når begge faktisk sendes) |

## Lukkbarhet pr. kanal
| Kanal | Kan lukkes? | Mekanisme (INAKTIVER) |
| --- | --- | --- |
| Min side brukervarsel | Ja | Publiser inaktiver-event (tms varsel, samme varselId = leveranse.id) |
| Dine Sykmeldte (NL) | Ja | Ferdigstill-hendelse på dinesykmeldte-topic |
| Ditt Sykefravær | Ja | Lukk/erstatt-melding |
| AG-notifikasjon (+Altinn) | Ja | Avledet fra lagret rad (B39): OPPGAVE→`oppgaveUtført`, BESKJED→`hardDelete`, sak→`nyStatusSak(FERDIG)` |
| Fysisk brev | **Nei** | Kan ikke trekkes tilbake (B3) |
| Mikrofrontend | Synlighet | «Lukking» = `disable`. Enable/disable-modellering avklares i kanal-DTO-område (3) |

## Ugyldige kombinasjoner (B21)
- Ulovlige kombinasjoner (f.eks. FERDIGSTILL + BREV) gjøres **urepresenterbare** i
  den typede kontrakten (sealed types) og i JSON Schema på topic-kontrakten →
  produsent får feil ved bygg/validering, ikke i drift.
- Runtime er **defense-in-depth**: skulle en ugyldig kombinasjon likevel nå inbox
  (schema-drift, gammel produsent) → inbox `BEHANDLET`, ingen leveranse,
  logg + metrikk `ugyldig_kombinasjon`. Ingen alert-storm, ingen FEILET.

## Kafka-semantikk (B21)
Konsumenten skriver hendelsen til inbox og **committer offset umiddelbart**. All
validering/forretningslogikk skjer senere i beslutnings-workeren på DB-raden, frakoblet
Kafka. En terminal DB-status (FEILET/DROPPET/«ugyldig») **blokkerer aldri partisjonen**
og gir ingen redelivery-loop.
