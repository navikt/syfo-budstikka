# Overordnet flyt — syfo-budstikka

Domeneblind varselruter. Konsument eier *hva/når*, budstikka eier *hvordan det leveres*.
Tre faser: **Inbox → Beslutning → Outbox**, decoupled workers med radlås.

```mermaid
flowchart TB
    subgraph Domene["Domeneapper (konsumenter)"]
        P1["isdialogmote"]
        P2["aktivitetskrav-backend"]
        P3["meroppfolging-backend"]
        P4["... øvrige domeneapper"]
    end

    P1 & P2 & P3 & P4 -->|"hendelse: eventId, referanse,<br/>mottaker, kanal, tekst, synligTom"| TOPIC{{"Kafka-topic<br/>team-esyfo.formidling.v1<br/>nøkkel = mottaker-id"}}

    subgraph Budstikka["syfo-budstikka"]
        direction TB
        CONS["Konsument<br/>(skriver kun til inbox)"]
        INBOX[("inbox<br/>dedup på eventId")]
        BESL["Beslutnings-worker<br/>(poller, radlås)"]
        ELIG{"Eligibility-gate"}
        OUTBOX[("outbox<br/>1 rad pr. konkret leveranse<br/>status + neste_forsok")]
        OWORK["Outbox-worker<br/>(poller, radlås)<br/>backoff + maxLeveringsalder"]
        DROP[("droppet/død-logg<br/>+ metrikk")]
        FEILET[("feilet<br/>permanent + alert")]
    end

    TOPIC --> CONS --> INBOX
    INBOX --> BESL --> ELIG
    ELIG -->|"død (PDL)"| DROP
    ELIG -->|"frys kanalvalg<br/>(KRR/reservasjon)<br/>1 DB-tx: skriv outbox + marker inbox"| OUTBOX
    OUTBOX --> OWORK

    OWORK -->|"transient feil"| RETRY{"innen<br/>maxLeveringsalder<br/>& synligTom?"}
    RETRY -->|"ja"| OUTBOX
    RETRY -->|"nei"| UTLOPT[("UTLOPT<br/>+ metrikk/alert")]
    OWORK -->|"permanent feil"| FEILET

    subgraph KRRPDL["Eksterne oppslag (Azure AD M2M)"]
        KRR["digdir-krr-proxy<br/>(reservasjon/digital)"]
        PDL["pdl-api<br/>(død)"]
    end
    BESL -.lesekall.-> KRR
    BESL -.lesekall.-> PDL

    subgraph Flater["Kanaler / flater ut"]
        MS["Min side brukervarsel<br/>(Kafka)"]
        DS["Dine Sykmeldte<br/>(Kafka)"]
        DSF["Ditt Sykefravær<br/>(Kafka)"]
        AG["AG-notifikasjon + Altinn<br/>(GraphQL)"]
        BREV["Fysisk brev<br/>(dokdistfordeling)"]
        MF["Mikrofrontend<br/>(Kafka)"]
    end

    OWORK -->|"levér / ferdigstill<br/>(idempotent pr. leveranse-id)"| MS & DS & DSF & AG & BREV & MF
    OWORK -->|"suksess → sendt + metrikk"| OK([" "])

    TRACE["trace-id propageres:<br/>produsent → Kafka-header → workers → levering → logg/Grafana"]
```

## Lesehjelp
- **Konsument → inbox:** rask, idempotent (dedup på `eventId`). KRR/PDL-treghet gir ikke Kafka-lag.
- **Beslutnings-worker:** kjører eligibility-gate. Død → droppet-logg. Ellers fryses kanalvalg
  (reservasjon påvirker kun ekstern varsling / brev-fallback) til konkrete outbox-rader i én DB-tx.
- **Outbox-worker:** «dum» utfører + retryer pr. leveranse. Transient → backoff til frist; permanent → feilet.
- **FERDIGSTILL:** egen hendelse, samme flyt; leveransen lukker tidligere varsel matchet på `referanse`
  (brev kan ikke lukkes).
- **Skalering:** radlås (`FOR UPDATE SKIP LOCKED`) lar flere podder dele last uten dobbeltlevering.
