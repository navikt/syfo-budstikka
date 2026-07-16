# FERDIGSTILL-flyt — syfo-budstikka

Hvordan budstikka lukker/inaktiverer et tidligere sendt varsel, uten domenekunnskap.
Avledet av B3, B4, B6 og B19–B21.

## Prinsipp
FERDIGSTILL er en **egen hendelse** på samme kontrakt og topic som OPPRETT, og går
gjennom **samme flyt og samme delivery-maskineri**. En lukking er bare en `delivery`-rad
med `operation=INAKTIVER` — den plukkes via claim/lease og er idempotent
(egen `delivery.id`) på lik linje med en utsending.

## Targeting (B19, B38)
- FERDIGSTILL er **typet pr. lukkbar kanal** (B38): kanal er implisitt i typen
  (`BrukervarselInactivate`, `LedervarselInactivate`, `DittSykefravaerInactivate`,
  `ArbeidsgivervarselInactivate`). Hendelsen er thin: `referanse` + typet **nøkkel**.
- Nøkkelen er typet (`PersonIdentifier`/`Orgnummer`) → bevarer PII-maskering (B9) og gjør
  ulovlige `(kanal, nøkkel)`-par urepresenterbare.
- Matching: budstikka slår opp åpen OPPRETT-leveranse på `(referanse, recipient_id, kanal)`,
  der `recipient_id` = matchnøkkelen = **OPPRETTs partisjonsanker** (det konsumenten kjenner):
  sykmeldt-fnr for BRUKERVARSEL/LEDERVARSEL/DITT_SYKEFRAVAER (B24: sykmeldt, ikke NL-fnr),
  orgnr for ARBEIDSGIVERVARSEL. Resolvert NL-fnr / `ekstern_respons_id` bor i payload/egne
  kolonner og deltar ALDRI i matching.
- Vil konsument lukke flere kanaler → sender én FERDIGSTILL pr. kanal. Budstikka
  bestemmer aldri scope/fan-out selv → forblir domeneblind.

## Lukkeoperasjon avledes fra lagret rad (B39)
FERDIGSTILL-hendelsen bærer ALDRI meldingstype/sti/operation. Beslutnings-workeren (B28)
finner matchende OPPRETT-leveranse, `decide()` fryser lukkeparametrene (`meldingstype`, sti
NL/Altinn, `ekstern_respons_id`, `grupperingsid`) onto INAKTIVER-leveransen, og `Kanalhandler`
(B27) dispatcher på disse **lagrede tekniske attributtene** — aldri på domenetype (B1/B30).
Kompatibelt med klebrig eierskap (B34): budstikka mottar kun FERDIGSTILL for OPPRETT den selv
laget → lagret rad finnes alltid.

## Edge-håndtering (B20)
| Situasjon | Handling |
| --- | --- |
| OPPRETT funnet, `SENT` | Normal: skriv `delivery(operation=INAKTIVER)` → outbox lukker på kanalen |
| OPPRETT funnet, fortsatt `READY` (ikke sendt) | Dagens modell har ingen egen `CANCELLED`-state. OPPRETT og FERDIGSTILL håndteres som egne delivery-rader i samme claim/lease-flyt. |
| Ingen matchende OPPRETT | Ikke hard feil: inbox → `PROCESSED`, ingen delivery-rad, logg + metrikk `ferdigstill_uten_treff`. (Partisjonsordning gjør «OPPRETT kommer senere» usannsynlig når begge faktisk sendes) |

## Lukkbarhet pr. kanal
| Kanal                     | Kan lukkes? | Mekanisme (INAKTIVER) |
|---------------------------| --- | --- |
| Min side brukervarsel     | Ja | Publiser inaktiver-event (tms varsel, samme varselId = `delivery.id`) |
| Dine Sykmeldte (NL)       | Ja | Ferdigstill-hendelse på dinesykmeldte-topic |
| Ditt Sykefravær           | Ja | Lukk/erstatt-melding |
| AG-notifikasjon (+Altinn) | Ja | Avledet fra lagret rad (B39): OPPGAVE→`oppgaveUtført`, BESKJED→`hardDelete`, sak→`nyStatusSak(FERDIG)` |
| Fysisk brev               | **Nei** | Kan ikke trekkes tilbake (B3) |
| Microfrontend             | Synlighet | «Lukking» = `disable`. Enable/disable-modellering avklares i kanal-DTO-område (3) |

## Ugyldige kombinasjoner (B21)
- Ulovlige kombinasjoner (f.eks. FERDIGSTILL + BREV) gjøres **urepresenterbare** i
  den typede kontrakten (sealed types) og i JSON Schema på topic-kontrakten →
  produsent får feil ved bygg/validering, ikke i drift.
- Runtime er **defense-in-depth**: skulle en ugyldig kombinasjon likevel nå inbox
  (schema-drift, gammel produsent) → inbox `PROCESSED`, ingen delivery-rad,
  logg + metrikk `ugyldig_kombinasjon`. Ingen alert-storm, ingen `FAILED`.

## Kafka-semantikk (B21)
Konsumenten skriver hendelsen til inbox og **committer offset umiddelbart**. All
validering/forretningslogikk skjer senere i beslutnings-workeren på DB-raden, frakoblet
Kafka. En terminal DB-status (`FAILED`/`DROPPED`/«ugyldig») **blokkerer aldri partisjonen**
og gir ingen redelivery-loop.
