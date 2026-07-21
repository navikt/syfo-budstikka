# 0008: Hydrert inbox — full parse ved ingest, eventId kun i header

- Status: besluttet (superseder ADR 0002; reverserer B54s «payload autoritativ»; nyanserer B4/B21, se `docs/context.md` B61)
- Dato: 2026-07-21
- Relatert: ADR 0002 (superseded), B4/B10/B18/B21/B22/B43/B54, issue #27 (sendevindu)

## Kontekst

ADR 0002 valgte parse-fri ingest: `eventId` ble lest fra Kafka-headeren som dedup-nøkkel,
rå payload ble lagret som `text`, og all deserialisering ble utsatt til beslutnings-workeren.
Hovedgrunnen var å holde dedup uavhengig av payload-skjemaet.

To ting har endret bildet:

1. Skjema-robustheten er allerede ivaretatt andre steder. `dispatchJson` har
   `ignoreUnknownKeys = true`, så additive feltendringer overlever en parse, og B43 gir
   topic-versjonering (`.v1`/`.v2` med dual-write) for breaking endringer. Det som da står
   igjen som «parse-feil», er nesten alltid et reelt kontraktbrudd (ukjent sealed-subtype
   eller korrupt konvolutt), og slikt bør uansett legges i dead-letter-tabellen.
   Robusthetsargumentet i ADR 0002 er derfor i praksis dekket.

2. FERDIGSTILL-lukking, og senere funksjonalitet, trenger strukturerte felt på inbox-raden.
   For å lukke eller annullere en melding som venter utenfor sendevinduet (#27), må
   `reference` være tilgjengelig på inbox-raden, ikke først når beslutnings-workeren
   deserialiserer. Å utsette parsingen tvinger fram enten deserialisering ved behov under
   lukking, eller et eget hydrert mellomlag (som vi forkastet som unødvendig komplekst).
   Å hydrere selve inbox-raden løser dette enklere.

Prinsippet som styrer valget: kontrakten vår er sannheten for hvilke data vi bryr oss om.
Sender en produsent nye felt før de har avklart det med oss, antar vi at de har dataene
lagret selv. Å håndtere ukjent data er ikke vårt ansvar (B1/B22).

## Beslutning

Ingest parser hele `Dispatch` (konvolutt og sealed `content`) og hydrerer inbox-raden.
Parsingen gjør bare syntaktisk kontraktvalidering og hydrering, ingen forretningslogikk.
Eligibility (død/KRR), kanalvalg, fallback og FERDIGSTILL-matching bor fortsatt i
beslutnings-workeren (B10/B28 er uendret).

1. eventId ligger bare i Kafka-headeren (`DispatchHeader.EVENT_ID`) og fjernes fra
   payloaden. Konvolutten blir `{ reference, content }`. eventId er en teknisk id for dedup
   og korrelasjon, ikke domenedata. Vi deduper på header-eventId som primærnøkkel
   (`ON CONFLICT DO NOTHING`). Headeren er dermed autoritativ og obligatorisk: mangler den
   eller er ugyldig, går meldingen i dead-letter-tabellen (`MISSING_EVENT_ID`/
   `INVALID_EVENT_ID`), uten fallback til payload. Dette reverserer B54, der payload var
   autoritativ. Vi unngår at samme verdi finnes to steder ved å ha eventId bare ett sted,
   ikke ved å rangere kildene.
2. `content` lagres som `jsonb<DispatchContent>` (samme som `delivery`), og rå `payload
   text` fjernes. Primærnøkkelen `event_id` settes fra headeren. `reference` løftes ut i en
   egen indeksert kolonne: den er den selektive match-nøkkelen ved FERDIGSTILL (B39), og det
   eneste konvolutt-feltet som ikke ligger i `content`-jsonb-en. `recipient`, `channel` og
   `operation` løftes ikke ut. De kan utledes fra `content` (`partitionKey`/`type`) og brukes
   bare til å avgrense innenfor et `reference`-treff. `ignoreUnknownKeys = true` beholdes, så
   ukjente felt faller bort med vilje. Flere indekserte match-kolonner på inbox legges bare
   til hvis `DECISIONS.md` #1 lander på inbox-hold; vi tar ikke den beslutningen her.
3. Dedup er uavhengig av skjemaet. Header-eventId leses og dedupes før payload parses, så en
   duplikat forkastes uten parsing, og dedup avhenger aldri av payload-skjemaet (samme
   robusthet som ADR 0002). For meldinger som havner i dead-letter-tabellen, lagrer vi
   eventId når vi kan: en melding som feiler på payload-parsing, men har en gyldig header,
   lagres med eventId, slik at vi kan korrelere mot produsenten. Mangler headeren, blir
   `event_id` null. Kolonnen er ny og nullable på `dead_letter_message`.
4. Feiltaksonomi ved ingest skiller syntaktisk fra semantisk ugyldighet:
   - Poison (syntaktisk) går i dead-letter: manglende eller ugyldig `eventId`-header, tom
     payload, korrupt JSON, konvolutt uten `reference`, eller content som ikke lar seg parse
     (ukjent sealed-subtype). Offset committes, og partisjonen flyter videre.
   - Representerbart, men ulovlig (semantisk) går ikke i dead-letter: en kombinasjon som
     parser til en gyldig type, men er ulovlig etter domene- eller kanalregler (B21), når
     inbox som normalt og håndteres av beslutnings-workeren (`PROCESSED`, ingen delivery,
     metrikk `ugyldig_kombinasjon`, ingen `FAILED` eller alert). B21s forsvar i dybden er
     uendret.
   - Transient (databasen nede): unntak kastes, ConsumerRunner committer ikke, og poller på
     nytt.
5. Replay etter en parser-oppgradering: en melding som havnet i dead-letter på grunn av
   ukjent subtype (versjonsavvik), kan spilles av på nytt etter at konsumenten er oppgradert,
   enten fra Kafka (innenfor 90-dagers retention, B26) med offset-reset, eller ved å sende
   inn dead-letter-raden på nytt. Begge er trygge fordi dedup skjer på header-eventId
   (`ON CONFLICT DO NOTHING`), så replay ikke dobbeltbehandler. Replay fra dead-letter er en
   manuell prosedyre, ikke noe automatisk.
6. Retensjon på `dead_letter_message` (utvider B42): tabellen bærer nå rå payload med fnr og
   tekst (nær særlige kategorier, art. 9) og er hovedstedet parse-feil havner. Den må derfor
   slettes like hardt som `inbox_message`: hard delete når raden er eldre enn ~100 dager
   (90-dagers replay-vindu fra B26 pluss litt margin), med samme periodiske slette-coroutine
   (B42). Vi lagrer minst mulig: rå bytes, tekniske Kafka-koordinater og eventId når vi har
   den. Parse-feil logges aldri rått (B58), bare feiltype og koordinater.
7. Hold-plassering (inbox-hold eller outbox-hold, `DECISIONS.md` #1) er fortsatt åpen, men
   ikke lenger blokkert: en hydrert inbox gjør både outbox-hold med `CANCELLED` og ekte
   inbox-hold (annullering før sending) billigere. Denne ADR-en tar ikke det valget.

## Konsekvenser

- ➕ `reference` (indeksert kolonne) og `content` (jsonb) på inbox gjør at FERDIGSTILL kan
  matche og avgrense ventende rader uten å parse på nytt; recipient og channel leses fra
  jsonb. Ingen kolonner på spekulasjon, bare det parse-ved-ingest faktisk trenger.
- ➕ eventId finnes bare ett sted (headeren), så vi slipper `payload.eventId ==
  header.eventId`-validering og risikoen for at de spriker.
- ➕ Dedup er uavhengig av skjemaet (headeren leses før parsing), samme robusthet som ADR
  0002: en duplikat forkastes uten parsing, og en feil i payload-skjemaet påvirker aldri
  dedup.
- ➕ Tilfellet «content lar seg ikke dekode, så beslutnings-workeren setter
  `inbox_message.FAILED`» (ADR 0002) faller bort: content er garantert parsebar på hver
  `RECEIVED`-rad, så `SerializationException` i beslutnings-workeren forsvinner (jf.
  PII-lekkasjen som ble tettet i B58; feilen flyttes til ingest og dead-letter, og logges
  aldri rått).
- ➕ Poison blokkerer fortsatt aldri partisjonen, og transiente feil taper aldri data i det
  stille.
- ➖ Ingest bindes igjen til payload-skjemaet: et reelt kontraktbrudd (ukjent subtype ved
  versjonsavvik) havner i dead-letter og krever manuell replay etter oppgradering (Beslutning
  pkt. 5). Bevisst akseptert, siden kontrakten er sannheten og B43 sender breaking endringer
  til `.v2`.
- ➖ Bare content som ikke lar seg parse, havner i dead-letter ved ingest. Kombinasjoner som
  er representerbare men ulovlige (B21) når inbox og håndteres av beslutnings-workeren som
  før (`PROCESSED` og metrikk, ingen `FAILED` eller alert). B21s forsvar er uendret.
- ➖ Ukjente additive felt beholdes ikke (jsonb, ikke rå bytes). Det tapet betyr ingenting
  for en domeneblind ruter, og rå-bytene beholdes uansett i `dead_letter_message` for
  feilsøking (tabellen har nå egen retensjon, Beslutning pkt. 6).
- ➖ Headeren blir en hard, obligatorisk avhengighet: en produsent som glemmer
  `DispatchHeader.EVENT_ID`, får alle meldinger i dead-letter, uten fallback til payload.
  Bevisst akseptert: eventId er en teknisk id, kontrakten er tydelig (delt konstant), og
  dead-letter-tabellen fanger avviket synlig i stedet for å tape data i det stille.
- 🔒 PII i ro: fnr ligger nå i `recipient_id` på inbox (som i `delivery`, B18/B42) og i rå
  payload i `dead_letter_message`. Retensjonsgulvet (inbox ≥ 90 dager, B26/B42) står, og
  dead-letter får eksplisitt hard-delete-retensjon (Beslutning pkt. 6, utvider B42).

## Alternativer vurdert

- Behold ADR 0002 (parse-fri) og deserialiser ved behov under lukking. Forkastet: flytter
  kompleksiteten inn i lukke-mekanikken uten å gi strukturerte felt til annen funksjonalitet,
  og hver forbruker må parse på nytt.
- Eget hydrert mellomlag (egen tabell) mellom inbox og outbox. Forkastet som unødvendig
  komplekst; vi hydrerer `inbox_message` direkte.
- `reference` som ny Kafka-header (utvid B54). Forkastet: gir matching uten parsing, men
  utvider header-kontrakten for alle produsenter og gjeninnfører faren for at header og
  payload spriker. Full parse gir alle felt uten å endre kontrakten.
- Behold `eventId` i payloaden som autoritativ, med headeren som en id vi lagrer når vi kan.
  Dette var det opprinnelige forslaget i ADR 0008. Teamet forkastet det til fordel for header
  alene: eventId er en teknisk id, ikke domenedata, og én kilde er ryddigere enn å rangere to.
  Kostnaden (hard avhengighet til headeren) er tatt bevisst.
- Behold både `eventId` i payload og header, og valider at de er like (B54). Forkastet: det
  er nettopp de to kildene ADR-en fjerner.
