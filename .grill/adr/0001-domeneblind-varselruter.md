# ADR 0001 — syfo-budstikka som domeneblind varselruter

- Status: Akseptert (grilling fase 1)
- Dato: 2026-06-29
- Erstatter: ansvar i `esyfovarsel`

## Kontekst

`esyfovarsel` er sentral varselruter for eSyfo, men bærer mye domenekunnskap om
andre apper (oppfølgingsplan, dialogmøte, aktivitetskrav): tekstkatalog, frist-
regler (`synligTom`), mikrofrontend-livssyklus og resend-logikk koblet til konkrete
domeneoppgaver. Det gjør appen vanskelig å endre og eie. syfo-budstikka skal overta
ansvaret for å nå brukere (sykmeldt, nærmeste leder, arbeidsgiver) uten å kjenne
domenene.

## Beslutning

Budstikka er en **domeneblind varselruter**. Domeneappen eier *hva* og *når*;
budstikka eier *hvordan det leveres*.

- Konsument leverer ferdig tekst og eksplisitt utløp (`synligTom`). Budstikka har
  ingen tekstkatalog eller domeneregler.
- Én Kafka-topic, enkelthendelse, gjenbrukt for alle mottakere. **Eksplisitt kanal**
  pr. hendelse med kanalspesifikt, typet payload. Mottaker + handling (OPPRETT/
  FERDIGSTILL) er felt, ikke topics.
- Idempotens via produsent-oppgitt `eventId` (inbox-dedup) + `referanse`
  (kobler FERDIGSTILL→OPPRETT). Partisjonsnøkkel = mottakerens id.
- Budstikka eier en alltid-på eligibility-gate (død → dropp + registrer; KRR/
  reservasjon → styrer kun ekstern varsling) og leveringsrobusthet.
- Arkitektur i tre faser: **Inbox → Beslutning (frys kanalvalg) → Outbox**
  (én rad pr. konkret leveranse, worker utfører + retryer).
- Sentral retry: eksponentiell backoff + `maxLeveringsalder`; `synligTom` kapper
  fristen tidligere. Transient vs permanent feil skilles. Aldri stille dropp.

## Konsekvenser

- ➕ Budstikka kan eies og endres uten domenekunnskap; nye domener krever ingen
  kodeendring, bare en ny konsument som sender riktig kanal/tekst.
- ➕ Idempotens og leveringsgaranti er strukturelt forankret (inbox/outbox), ikke
  ad hoc cron-resend.
- ➖ Domeneappene må nå eie tekst, utløp og kanalvalg selv (flyttet ansvar).
- ➖ «Send brev hvis reservert» krever at brukernotifikasjons-hendelsen bærer
  `brevFallback` med journalpostId — litt mer for konsument å fylle ut.
- Migrering fra esyfovarsel må håndtere sameksistens (egen ADR).
