# 0008: Hydrert inbox — full parse ved ingest, eventId kun i header

- Status: besluttet (superseder ADR 0002; reverserer B54s «payload autoritativ»; nyanserer B4/B21, se `docs/context.md` B61)
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

1. **`eventId` lever KUN i Kafka-headeren** (`DispatchHeader.EVENT_ID`) og fjernes fra
   payloaden — `Dispatch`-konvolutten blir `{ reference, content }`. eventId er
   transport-plumbing (dedup + korrelasjon), ikke domenedata. Dedup på **header-eventId**
   som PK (`ON CONFLICT DO NOTHING`). Headeren er dermed **autoritativ og obligatorisk**:
   manglende/ugyldig header → dead-letter (`MISSING_EVENT_ID`/`INVALID_EVENT_ID`), ingen
   payload-fallback. Dette *reverserer* B54s «payload forblir autoritativ», og løser
   tvillingkilde-problemet ved å ELIMINERE den ene kilden, ikke ved å rangere dem.
2. **`content` lagres som `jsonb<DispatchContent>`** (speiler `delivery`); rå `payload
   text` droppes. `event_id` (PK) settes fra headeren. `reference` løftes ut som egen
   indeksert kolonne — den er den selektive FERDIGSTILL-match-nøkkelen (B39) OG det eneste
   konvolutt-feltet som ikke ligger i `content`-jsonb-en. `recipient`/`channel`/`operation`
   løftes **ikke** ut: de er utledbare fra `content` (`partitionKey`/`type`) og brukes kun
   til å avgrense innenfor et `reference`-treff. `ignoreUnknownKeys = true` beholdes →
   ukjente felt forsvinner **bevisst**. (Ytterligere indekserte match-kolonner på inbox
   legges til KUN hvis `DECISIONS.md` #1 lander på inbox-hold — ikke tatt på forskudd her.)
3. **Dedup er skjema-uavhengig:** header-eventId leses og dedupes FØR payload parses, så
   en dupliserende melding forkastes uten parsing, og dedup avhenger aldri av payload-
   skjemaet (gjenvinner ADR 0002s robusthet). For dead-letter beholdes eventId best-effort
   som ny nullable `event_id`-kolonne på `dead_letter_message`: en melding som feiler
   *payload*-parse men har gyldig header lagres MED eventId → full korrelasjon til produsent;
   kun en melding som mangler headeren får `event_id = null`.
4. **Feiltaksonomi ved ingest — skill *syntaktisk* fra *semantisk* ugyldighet:**
   - *Poison (syntaktisk, → dead-letter):* manglende/ugyldig `eventId`-header, tom payload,
     korrupt JSON, konvolutt uten `reference`, eller **parser-urepresenterbar** content
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
   raden. Begge er trygge fordi dedup er på header-eventId (`ON CONFLICT DO NOTHING`)
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

- ➕ `reference` (indeksert kolonne) + `content` (jsonb) på inbox → FERDIGSTILL kan
   matche/avgrense ventende rader uten re-parsing; recipient/channel leses fra jsonb.
   Ingen spekulativ schema — kun det parse-ved-ingest faktisk trenger.
- ➕ B54s tvillingkilde-kompleksitet forsvinner: eventId lever ett sted (headeren), så
   ingen `payload.eventId == header.eventId`-validering og ingen divergensrisiko.
- ➕ Dedup er skjema-uavhengig (header leses før parse) → gjenvinner ADR 0002s robusthet:
   en duplikat forkastes uten parsing, og en payload-skjemafeil påvirker aldri dedup.
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
- ➖ Headeren blir en **hard, obligatorisk avhengighet**: en produsent som utelater
   `DispatchHeader.EVENT_ID` får ALLE meldinger dead-lettet, uten payload-fallback. Bevisst
   akseptert — eventId er transport-plumbing og kontrakten er eksplisitt (delt konstant);
   `dead_letter_message` fanger avviket observerbart framfor stille datatap.
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
- **Behold `eventId` i payloaden (autoritativ), header som best-effort brødsmule.** Var
  det opprinnelige ADR-0008-forslaget; forkastet av teamet til fordel for header-kun —
  eventId er transport-plumbing, ikke domenedata, og ett kildested er renere enn å rangere
  to. Kostnaden (hard header-avhengighet) tatt bevisst.
- **Behold både payload-`eventId` OG header (validér likhet, B54).** Vraket: nettopp
  tvillingkilden ADR-en fjerner.

