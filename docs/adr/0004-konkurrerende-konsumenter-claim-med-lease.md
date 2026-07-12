# 0004: Konkurrerende konsumenter på inbox — claim med SKIP LOCKED og lease

- Status: besluttet (issue #56, beslutnings-worker)
- Dato: 2026-07-10
- Relatert: ADR 0002 (inbox-header-dedup), ADR 0003 (application-lag), `docs/datamodell.md`, beslutning B28 i `docs/context.md`

## Kontekst

`InboxMessageWorker` skal kunne kjøre i **flere replicaer samtidig** — det er et hardt krav. Uten
koordinering ville to replicaer polle de samme `RECEIVED`-radene, slå opp de samme personene i
PDL/KRR (unødig last på eksterne API-er) og i verste fall skrive `delivery`-rader dobbelt.

Effektueringen er allerede delt i to transaksjoner fordi grunnlagsinnhentingen (PDL/KRR) IKKE kan
skje inne i en databasetransaksjon — en pooled connection skal aldri holdes åpen over nettverks-I/O.
Når poll og effektuering er delt, kan ikke en enkelt rad-lås beskytte hele livssyklusen. Da trengs en
eksplisitt koordineringsmekanisme.

Vurderte familier:

1. **Hold låsen (én rad om gangen, `FOR UPDATE` som spenner hele behandlingen).** Krasj-sikkert
   gratis (aborter → lås slippes), men kjører ekstern I/O inne i transaksjonen og binder en
   DB-connection per melding under oppslag. Vraket: velkjent anti-mønster (lange transaksjoner
   bloater Postgres, tømmer connection-poolen).
2. **Optimistisk (ingen claim-tilstand, kun atomisk `RECEIVED→PROCESSED`-CAS ved effektuering).**
   Krasj-sikkert og enkelt, men flere replicaer kan slå opp samme melding før én vinner CAS-en →
   **dublett-oppslag mot eksterne API-er**. Vraket: hamrer PDL/KRR.
3. **Leder-valg (én aktiv poller).** Vraket eksplisitt: vi vil ha ekte konkurrerende konsumenter, ikke
   én arbeidende node.

## Beslutning

Vi bruker **transactional-inbox-standarden for konkurrerende konsumenter**: claim med
`FOR UPDATE SKIP LOCKED` + lease (visibility timeout), og exactly-once-levering via en atomisk
terminal-CAS. Konkret:

1. **Claim (én transaksjon).** Workeren plukker en bunke:
   `SELECT … WHERE state='RECEIVED' OR (state='CLAIMED' AND next_attempt_time <= now())
   ORDER BY received_at, event_id LIMIT :batch FOR UPDATE SKIP LOCKED`,
   og setter i samme transaksjon radene til `state='CLAIMED'`, `attempt = attempt + 1`,
   `next_attempt_time = now() + lease`. `SKIP LOCKED` gir hver replica en **disjunkt** bunke uten å
   blokkere. Etter commit er radene usynlige for andre pollere (`state ≠ RECEIVED`).
2. **Enrich (ingen transaksjon).** Grunnlagsinnhenting per melding skjer utenfor DB-transaksjonen.
3. **Effectuate (én transaksjon per melding).** `delivery`-rad(er) + terminal inbox-status commits
   alt-eller-ingenting (jf. per-melding-atomisk-invarianten). Den atomiske **CAS-en kjøres først**:
   `UPDATE … SET state='PROCESSED' WHERE event_id=? AND state='CLAIMED'`. Bare den workeren som
   vinner (rowcount=1) skriver `delivery`-radene; en taper skriver ingenting.
4. **Krasj-gjenoppretting er automatisk, ingen reaper-worker.** En død worker sine `CLAIMED`-rader blir
   claimbare igjen når leasen utløper — selve claim-spørringen plukker dem opp. Kolonnene `attempt`
   og `next_attempt_time` er designet for nettopp dette.
5. **At-least-once enrichment, exactly-once delivery.** På happy path slås hver melding opp én gang.
   Dublett-oppslag skjer bare etter krasj/lease-utløp (sjelden, avgrenset), og terminal-CAS-en hindrer
   dobbel levering selv om to workere kappløper om en utløpt lease.
6. **Lease-varighet er konfigurerbar** (`workers.inboxMessage.leaseSeconds`), default **5 minutter**;
   `batchSize` senkes fra 100 til **25**. For kort lease er den farlige retningen (en frisk worker
   mister claimet midt i arbeidet → dublett-oppslag); for lang lease forsinker bare gjenoppretting.
   Leasen stemples på hele bunka ved claim, så den må dekke `batchSize × per-melding` (batch-drain) —
   derfor en generøs lease og en moderat bunke.

Ingen migrasjon kreves: `state` er ren `TEXT` uten CHECK-constraint (så `CLAIMED` er bare en ny
verdi), og indeksen `(state, next_attempt_time)` dekker claim-spørringen.

## Konsekvenser

- ➕ Flere replicaer kan polle trygt; `SKIP LOCKED` gir disjunkte bunker uten blokkering.
- ➕ Krasj-sikkert uten egen reaper — leasen er gjenopprettingsmekanismen.
- ➕ Eksterne API-er hamres ikke: én oppslag per melding på happy path.
- ➕ Gjenbruker den atomiske effektuerings-seamen; ingen schema-migrasjon.
- ➖ En død worker sine rader venter én lease (default 5 min) før de behandles på nytt — bevisst
   avveining (heller sen gjenoppretting enn dublett-oppslag).
- ➖ `CLAIMED` innfører en ikke-terminal tilstand i state-maskinen som drift må forstå (en rad kan stå
   `CLAIMED` mens en lease løper).
- ➖ Ingen heartbeat/lease-forlengelse ennå: en enkeltmelding som tar lengre enn leasen vil bli
   re-claimet. Akseptabelt ved en generøs lease; heartbeat innføres først om vi trenger både lang
   sikkerhetsmargin og rask gjenoppretting.

## Alternativer vurdert

Se familiene 1–3 i kontekst-seksjonen over. De er beholdt der siden de forklarer hvorfor vi landet på
claim + lease, og hva som ble vraket.

Utsatt (dokumentert, ikke besluttet nå): indeks-tuning for claim-spørringens `ORDER BY received_at`
(nåværende `(state, next_attempt_time)`-indeks holder ved forventet volum), og
heartbeat/lease-forlengelse.
