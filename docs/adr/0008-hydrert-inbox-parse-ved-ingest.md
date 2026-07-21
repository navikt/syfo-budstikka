# 0008: Hydrert inbox — full parse ved ingest, dedup på payload

- Status: besluttet (superseder ADR 0002; nyanserer B54/B21, se `docs/context.md` B61)
- Dato: 2026-07-21
- Relatert: ADR 0002 (superseded), B4/B10/B18/B21/B22/B43/B54, issue #27 (sendevindu)

## Kontekst

ADR 0002 valgte **parse-fri ingest**: `eventId` fra Kafka-header som dedup-nøkkel,
rå payload lagret byte-eksakt som `text`, og all deserialisering deferret til
beslutnings-workeren. Hovedbegrunnelsen var å løsrive dedup fra payload-skjemaet.

To ting har endret bildet:

1. **Skjema-robustheten er allerede dekket andre steder.** `dispatchJson` har
   `ignoreUnknownKeys = true` (additive feltendringer overlever parse), og B43 gir
   topic-versjonering (`.v1`/`.v2` + dual-write) for breaking endringer. Det som da
   gjenstår som «parse-feil» er nesten utelukkende et *ekte kontraktbrudd* (ukjent
   sealed-subtype, korrupt konvolutt) — som uansett bør dead-letteres. ADR 0002s
   robusthetsargument er dermed i praksis subsumert.

2. **FERDIGSTILL-lukking og framtidig funksjonalitet trenger strukturerte felt på
   inbox-raden.** For å lukke/annullere en melding som ligger og venter (utenfor
   sendevindu, #27) må `reference` + recipient + channel være tilgjengelig på inbox-
   raden, ikke først når beslutnings-workeren dekoder. Å deferre parsing tvinger enten
   on-the-fly-deserialisering ved lukking eller et eget hydrert mellomlag (vraket som
   overengineering). Hydrering av inbox-raden selv gir dette rom generelt.

Prinsipp som styrer valget: **vår kontrakt er sannheten for hvilke data vi bryr oss
om.** Kaster en produsent nye felt på oss før de har snakket med oss, antar vi at de
har dem lagret hos seg — å håndtere ukjent data er utenfor vårt ansvar (B1/B22).

## Beslutning

Ingest **parser hele `Dispatch`** (konvolutt + sealed `content`) og hydrerer
inbox-raden. Parsingen er **kun syntaktisk kontraktvalidering + hydrering** — ingen
forretningslogikk (eligibility/død/KRR, kanalvalg, fallback, FERDIGSTILL-matching) bor
fortsatt i beslutnings-workeren (B10/B28 uendret; se «Konsekvenser»). Konkret:

1. **Dedup på `payload.eventId`** (autoritativ, B4/B43), fortsatt PK / `ON CONFLICT DO
   NOTHING`. Headeren er ikke lenger dedup-nøkkel.
2. **Strukturerte kolonner løftes ut** på `inbox_message`: `reference`,
   `recipient_type`, `recipient_id`, `channel`, `operation`. `content` lagres som
   `jsonb<DispatchContent>` (speiler `delivery`); rå `payload text` droppes.
   `ignoreUnknownKeys = true` beholdes — ukjente felt forsvinner **bevisst**.
3. **`eventId`-headeren (B54) degraderes** til en ikke-autoritativ, best-effort
   korrelasjons-brødsmule: leses **kun i dead-letter-grenen** (der payloaden per
   definisjon ikke kan gi id-en) og lagres som ny nullable `event_id`-kolonne på
   `dead_letter_message`. For gyldige rader røres headeren aldri → ingen
   `header == payload`-validering, ingen tvillingkilde. Produsentene endrer ingenting.
   (Bekreftet 2026-07-21 — ikke lenger et åpent veto-punkt.)
4. **Feiltaksonomi ved ingest — skill *syntaktisk* fra *semantisk* ugyldighet:**
   - *Poison (syntaktisk, → dead-letter):* manglende/tom payload, korrupt JSON,
     konvolutt uten `eventId`/`reference`, eller **parser-urepresenterbar** content
     (ukjent sealed-subtype). Offset committes → partisjonen flyter.
   - *Representerbart-men-ulovlig (semantisk, → IKKE dead-letter):* en kombinasjon som
     parser til en gyldig type men er domene-/kanalulovlig (B21) når inbox som normalt,
     og håndteres av beslutnings-workeren per B21 (`PROCESSED`, ingen delivery, metrikk
     `ugyldig_kombinasjon` — ingen `FAILED`/alert). Ingest DL-er den **ikke**; B21s
     defense-in-depth består uendret.
   - *Transient (DB nede):* unntak kastes → ConsumerRunner committer ikke, re-poller.
5. **Replay-prosedyre ved parser-oppgradering:** en melding dead-lettet pga. ukjent
   subtype/version-skew replayes etter at konsumenten er oppgradert — enten fra Kafka
   (innen 90d retention, B26) via offset-reset, eller re-injiseres fra `dead_letter_message`-
   raden. Begge er trygge fordi dedup er på `payload.eventId` (`ON CONFLICT DO NOTHING`)
   → replay dobbeltbehandler ikke. DL-replay er en operasjonell prosedyre, ikke automatikk.
6. **Retensjon på `dead_letter_message` (utvider B42):** DL bærer nå rå payload med
   fnr/tekst (art.9-nær) og er primær landingsplass for parse-feil → må ha samme
   hard-delete-disiplin som `inbox_message`: hard delete ved alder > ~100 dager (≥ 90d
   replay-vindu B26 + buffer), gjenbruker samme periodiske slette-coroutine (B42).
   Lagret innhold minimeres (rå bytes + tekniske Kafka-koordinater + best-effort
   `event_id`); ingen parse-feilmelding logges rått (B58 — kun feiltype/koordinater).
7. **Hold-plassering (inbox- vs. outbox-hold, `DECISIONS.md` #1) forblir åpen**, men
   er nå avblokkert: en hydrert inbox gjør både outbox-hold-med-`CANCELLED` og ekte
   inbox-hold (pre-send-annullering) billigere. Denne ADR-en tar ikke det valget.

## Konsekvenser

- ➕ `reference`/recipient/channel er SQL-spørrbare på inbox → FERDIGSTILL kan matche/
   annullere ventende rader uten re-parsing; gir rom for framtidig funksjonalitet.
- ➕ B54s tvillingkilde-kompleksitet forsvinner: ingen `payload.eventId ==
   header.eventId`-validering, én autoritativ kilde (payloaden).
- ➕ «Undecodable content → `inbox_message.FAILED` hos beslutnings-worker» (ADR 0002)
   utgår: content er garantert parsebar på hver `RECEIVED`-rad, så
   `SerializationException` i beslutnings-workeren elimineres (jf. PII-lekkasjen tettet
   i B58 — feilflaten flyttes til ingest→DL, aldri logget rått).
- ➕ Poison blokkerer fortsatt aldri partisjonen; transient taper aldri data stille.
- ➖ Ingest binder seg igjen til payload-skjemaet: et ekte kontraktbrudd (ukjent
   subtype under versjons-skew) dead-letteres og krever manuell replay etter oppgradering
   (Beslutning pkt. 5) — bevisst akseptert (kontrakt = sannhet; B43 sender breaking til `.v2`).
- ➖ Kun *parser-urepresenterbar* content dead-letteres ved ingest; *representable-men-
   ulovlige* kombinasjoner (B21) når inbox og håndteres av beslutnings-workeren som før
   (`PROCESSED` + metrikk, ingen `FAILED`/alert). B21s runtime-forsvar er uendret.
- ➖ Ukjente additive felt bevares ikke (jsonb, ikke rå bytes). Verdiløst tap for en
   domeneblind ruter; de forensisk interessante bytene beholdes rått i
   `dead_letter_message` (som nå har egen retensjon, Beslutning pkt. 6).
- 🔒 PII-at-rest: fnr i `recipient_id` nå også på inbox (som `delivery`, B18/B42) OG rå
   payload m/fnr i `dead_letter_message`. Retensjonsgulv (inbox ≥ 90d, B26/B42) står;
   DL får eksplisitt hard-delete-retensjon (Beslutning pkt. 6, utvider B42s scope).

## Alternativer vurdert

- **Behold ADR 0002 (parse-fri) + on-the-fly-deserialisering ved lukking.** Vraket:
  flytter kompleksitet inn i lukke-mekanikken uten å gi strukturerte felt for annen
  funksjonalitet; hver forbruker må re-parse.
- **Eget hydrert mellomlag (egen tabell) mellom inbox og outbox.** Vraket som
  overengineering — hydrer `inbox_message` på plass.
- **`reference` som ny Kafka-header (utvid B54).** Vraket: parse-fri matching, men
  utvider header-kontrakten over alle produsenter + gjeninnfører tvillingkilde-
  divergens; full parse gir alle felt uten kontraktendring.
- **Fjern `eventId`-headeren helt.** Vraket: den koster ~ingenting og gir reell
  korrelasjonsverdi på dead-letter-rader (eneste parse-uavhengige identitet der).

