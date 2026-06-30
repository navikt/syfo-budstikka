# FERDIGSTILL-flyt — syfo-budstikka

Hvordan budstikka lukker/inaktiverer et tidligere sendt varsel, uten domenekunnskap.
Avledet av B3, B4, B6 og B19–B21.

## Prinsipp
FERDIGSTILL er en **egen hendelse** på samme kontrakt og topic som OPPRETT, og går
gjennom **samme flyt og samme outbox-maskineri**. En lukking er bare en `leveranse`-rad
med `operasjon=INAKTIVER` — den plukkes, retryes (backoff/frist) og er idempotent
(egen `leveranse.id`) på lik linje med en utsending.

## Targeting (B19)
- FERDIGSTILL er **kanal-eksplisitt**: hendelsen bærer `kanal` + `referanse`
  (referanse er pr. kanal/mottaker, symmetrisk med OPPRETT, jf. B6).
- Matching: budstikka slår opp åpen OPPRETT-leveranse på `(referanse, mottaker_id, kanal)`.
- Vil konsument lukke flere kanaler → sender én FERDIGSTILL pr. kanal. Budstikka
  bestemmer aldri scope/fan-out selv → forblir domeneblind.

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
| AG-notifikasjon (+Altinn) | Ja | GraphQL: oppgave→utført / lukk sak |
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
