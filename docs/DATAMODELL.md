# Datamodell — syfo-budstikka

Avledet av B1–B18. To tabeller: **`inbox_hendelse`** (transport + dedup + beslutning)
og **`leveranse`** (outbox: frosne konkrete utsendinger). Postgres. Topologi A
(jf. B13) — kan utvides til varsel-aggregat senere (additiv migrering).

```mermaid
erDiagram
    inbox_hendelse ||--o{ leveranse : "0..N (frosset av gate)"

    inbox_hendelse {
        uuid        event_id PK "produsent-oppgitt, dedup"
        text        referanse "kobler OPPRETT/FERDIGSTILL"
        text        handling "OPPRETT | FERDIGSTILL"
        text        kanal
        text        mottaker_type "SYKMELDT | NL | AG"
        text        mottaker_id "fnr/nl-fnr/orgnr (PII)"
        jsonb       payload "rå hendelse"
        text        trace_id
        text        status "MOTTATT|BEHANDLET|DROPPET|FEILET"
        text        drop_aarsak "DOD | ... (nullable)"
        int         forsok
        timestamptz neste_forsok_tid
        timestamptz mottatt_tid
        timestamptz behandlet_tid "nullable"
        text        feilmelding "nullable"
    }

    leveranse {
        uuid        id PK "= idempotensnøkkel mot kanal"
        uuid        inbox_event_id FK
        text        referanse "FERDIGSTILL-oppslag"
        text        operasjon "OPPRETT | INAKTIVER"
        text        kanal
        text        mottaker_type
        text        mottaker_id "PII"
        jsonb       payload "frosset innhold: tekst, synlig_tom, eksternVarsling, journalpostId"
        text        status "KLAR|SENDT|FEILET_PERMANENT|UTLOPT"
        int         forsok
        timestamptz neste_forsok_tid
        timestamptz tidligst_sending "sendevindu-gate (B25), default=opprettet"
        timestamptz frist_tid "maxLeveringsalder, kappet av synlig_tom"
        text        ekstern_respons_id "kanalens retur-id (nullable)"
        text        feilmelding "nullable"
        text        trace_id
        timestamptz opprettet
        timestamptz sendt_tid "nullable"
    }
```

## Tilstandsmaskiner

### inbox_hendelse.status
```
MOTTATT ──(gate ok, skriv leveranser)──▶ BEHANDLET   (terminal)
MOTTATT ──(død via PDL)───────────────▶ DROPPET      (terminal, drop_aarsak=DOD)
MOTTATT ──(transient: PDL/KRR nede)───▶ MOTTATT      (forsok++, neste_forsok_tid backoff)
MOTTATT ──(permanent: ugyldig payload)▶ FEILET       (terminal, alert)
```
Settes utelukkende av **beslutnings-workeren**, i samme DB-tx som skriver leveranse(r)
eller dropper. Konsument skriver kun `MOTTATT`.

### leveranse.status
```
KLAR ──(sendt ok)────────────────▶ SENDT             (terminal)
KLAR ──(transient feil)──────────▶ KLAR              (forsok++, neste_forsok_tid backoff)
KLAR ──(permanent feil, 4xx)─────▶ FEILET_PERMANENT  (terminal, alert)
KLAR ──(frist_tid/synlig_tom)────▶ UTLOPT            (terminal, alert)
KLAR ──(FERDIGSTILL før sending)─▶ KANSELLERT        (terminal, jf. B20)
```
Transient feil er ikke egen status — raden blir i `KLAR` med backoff. Aldri stille dropp.
`KANSELLERT` settes når en FERDIGSTILL treffer en ennå-`KLAR` OPPRETT (lokal annullering,
ingen utsending + lukking). En INAKTIVER-leveranse er en egen rad (`operasjon=INAKTIVER`)
som går gjennom samme KLAR→SENDT-løp.

## Workere (polling, radlås)
- **Beslutnings-worker:** `SELECT … FROM inbox_hendelse WHERE status='MOTTATT'
  AND neste_forsok_tid <= now() FOR UPDATE SKIP LOCKED`. Eksterne lesekall (PDL/KRR)
  først, så én tx: oppdater inbox + insert leveranse(r).
- **Outbox-worker:** `SELECT … FROM leveranse WHERE status='KLAR'
  AND neste_forsok_tid <= now() AND tidligst_sending <= now() FOR UPDATE SKIP LOCKED`.
  Holder radlås under sending (B15), stramme klient-timeouts. Idempotensnøkkel =
  `leveranse.id` (B16). `tidligst_sending` gater sendevindu (B25) — beregnes i
  Beslutning-fasen fra `sendevindu` + NKS-kalender; budstikka sender alltid LØPENDE
  nedstrøms (self-operasjonalisert), så hele ventetiden er synlig i vår egen DB.

## Indekser
- `inbox_hendelse`: PK(`event_id`); idx(`status`,`neste_forsok_tid`) for plukk.
- `leveranse`: PK(`id`); idx(`status`,`neste_forsok_tid`,`tidligst_sending`) for plukk
  (sendevindu-gate, B25); idx(`referanse`,`mottaker_id`,`kanal`) for FERDIGSTILL-oppslag;
  idx(`inbox_event_id`).

## Observability-koblinger (jf. B17)
- `trace_id` på begge tabeller; strukturert logg ved hver overgang med
  `leveranse_id`/`referanse`/`kanal`/`status`/`trace_id`.
- Prometheus-metrikker kun lav kardinalitet (`kanal`,`status`,`mottaker_type`,`feiltype`).
  Drill-down til enkelt-id via Loki/Tempo, ikke metrikk-labels.

## Åpne punkter
- Retensjon/sletting av PII (fnr) — GDPR. Eget designpunkt. GULV satt av B26:
  inbox-dedup-rader (event_id) MÅ holdes ≥ 90 dager (= topic-retention), ellers gir
  Kafka-replay dobbeltvarsling. Leveranse-rader kan ha egen (kortere?) retensjon.
- FERDIGSTILL-flyt i detalj (matching, hvilke kanaler kan lukkes) — område 2.
- `payload`-skjema pr. kanal (typet DTO ↔ jsonb) — kanal-DTO-område (3).
