# Replay av dead letters

## Kort oppsummering

Når en melding ikke lar seg deserialisere, havner den i `dead_letter_message`
med `failure_reason = 'UNPARSEABLE_PAYLOAD'`. Kafka-offseten er allerede
committet, så meldingen kommer ikke tilbake av seg selv.

Replay er en **manuell engangsprosedyre** (ADR 0008 punkt 5): du skrur på et
miljøvariabel-flagg, restarter poden, verifiserer i loggen, og skrur flagget av
igjen. Det finnes ingen HTTP-rute og ingen automatisk bakgrunnsjobb.

## Når du bruker den

Etter at du har rettet årsaken til at meldingene ikke kunne parses — typisk en
endring i en `Create`-klasse eller i kontrakten — og den rettelsen er deployet.

**Rett årsaken først.** Replayer du før fiksen er ute, blir radene lest, hoppet
over som fortsatt uparsebare, og blir liggende. Ingen skade, men ingen nytte.

## Hva replayen gjør

1. Leser rader med `failure_reason = 'UNPARSEABLE_PAYLOAD'` og `event_id IS NOT NULL`.
2. Deserialiserer hver rad **i koden**, ikke i SQL. Inboxen er hydrert (ADR 0008):
   `inbox_message.content` skal være ferdig parset, og workeren stoler på det.
   En rå SQL-INSERT ville brutt den garantien og flyttet feilen inn i worker-loopen.
3. Skriver de parsebare radene til `inbox_message`, og sletter dem så fra
   `dead_letter_message`.
4. Rader som fortsatt ikke lar seg parse, blir **aldri slettet**. De hoppes over
   og telles som `skipped_count`.

Insert committes før delete, i to separate transaksjoner. Krasjer poden imellom,
gjør en ny replay ingen skade: `event_id` er primærnøkkel i `inbox_message`, og
innsettingen bruker `ON CONFLICT DO NOTHING`.

## Innstillinger

| Miljøvariabel | Standard | Betydning |
|---|---|---|
| `DEAD_LETTER_REPLAY_ENABLED` | `false` | Må være `true` for at replay skal kjøre |
| `DEAD_LETTER_REPLAY_BATCH_SIZE` | `100` | Antall rader per spørring |

Er en av verdiene ugyldig — for eksempel `1oo` eller `kanskje` — starter appen
som normalt, replay hoppes over, og feilen logges som `ERROR`. Replay er en
frivillig opprydding og skal aldri kunne ta ned tjenesten.

## Slik kjører du

### 1. Tell hvor mange rader som venter

```sql
SELECT count(*) FROM dead_letter_message
WHERE failure_reason = 'UNPARSEABLE_PAYLOAD' AND event_id IS NOT NULL;
```

### 2. Skru på flagget

```
nais app -e <nais env> <app> DEAD_LETTER_REPLAY_ENABLED=true
```

For eksempel:

```
nais app -e dev-gcp syfo-budstikka DEAD_LETTER_REPLAY_ENABLED=true
```

**Poden må restartes etterpå.** Miljøvariabler leses ved oppstart, så en
kjørende pod plukker ikke opp den nye verdien:

```
kubectl rollout restart deployment syfo-budstikka
```

### 3. Verifiser i loggen

Se etter denne linjen:

```
Dead-letter replay completed replayed_count=<n> skipped_count=<m>
```

- `replayed_count` — rader som nå ligger i inboxen.
- `skipped_count` — rader som fortsatt ikke lot seg parse. Blir liggende i
  `dead_letter_message` for videre undersøkelse.

Ser du `Dead-letter replay stopped after reaching batch limit` i stedet, traff
kjøringen taket på 1000 batcher. Kjør en gang til.

### 4. Skru av flagget igjen

```
nais app -e <nais env> <app> DEAD_LETTER_REPLAY_ENABLED=false
kubectl rollout restart deployment syfo-budstikka
```

Lar du flagget stå på, kjører replayen ved hver eneste oppstart. Den er
idempotent, så det er ufarlig, men det forsinker oppstart uten grunn.

### 5. Tell på nytt

Kjør spørringen fra steg 1 igjen. Ligger det fortsatt igjen replaybare rader,
se «Flere poder samtidig» nedenfor.

## Ting du bør vite

### Flere poder samtidig

`nais/nais-prod.yaml` har `replicas.min: 2`, så replayen kjører på alle poder
samtidig ved deploy. Det gir verken duplikater eller tap — men pod A kan slette
rader mens pod B paginerer, slik at én passering hopper over noen rader. De blir
liggende i `dead_letter_message`.

Derfor steg 5: tell på nytt, og kjør en gang til hvis det ligger igjen noe.

Merk også at `replayed_count` og `skipped_count` logges **per pod**. I Loki ser
tallene dermed doble ut.

### Replay blokkerer oppstart

Replayen kjører synkront før helsesjekk-endepunktene monteres. Varer den lenger
enn omtrent 60 sekunder, rekker liveness-proben å slå inn og poden restarter.
Det er selvhelbredende — hver batch committer for seg, og replayen er idempotent
— men det gir restart-støy. Sjekk radantallet i steg 1 før du kjører.

### Ingenting sensitivt logges

`dead_letter_message.payload` inneholder fødselsnummer og helseopplysninger.
Loggingen tar derfor bare med `event_id`, unntakstype og aggregerte tall — aldri
payload, feilmelding eller stacktrace.
