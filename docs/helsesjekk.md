# Helsesjekk for Kafka-consumer

## Kort oppsummering

En Kafka-consumer hører hjemme i **liveness** (`is_alive`), ikke i **readiness** (`is_ready`). Bruk en **selvrapportert hjerteslag-verdi (heartbeat)**, aldri en ping mot brokeren.

## Readiness mot liveness

| Probe | Spørsmål | Kafka-consumer? |
|-------|----------|-----------------|
| `is_ready` | Kan poden ta imot trafikk nå? | **Nei.** Consumeren henter fra en topic og betjener ikke HTTP. En død consumer skal ikke ta poden ut av lastbalanseringen. |
| `is_alive` | Er poden ødelagt uten mulighet for å komme seg — skal den restartes? | **Ja.** En consumer-loop som har låst seg eller dødd, er et gyldig signal om restart. |

## Mønsteret: selvrapportert heartbeat

1. Consumer-loopen oppdaterer et heartbeat-tidsstempel i hver poll-runde.
2. Liveness-sjekken rapporterer usunn kun når siste poll er for gammel (eldre enn en terskel).
3. `is_alive` svarer 503 når verdien er utdatert, og Kubernetes restarter poden.

```
consumer-loop:  poll() → recordPoll() → handle(records)   (hver runde)
is_alive:       er lastPoll fersk?  → 200 : 503
```

## Regler som avgjør om det fungerer

- **Oppdater også ved tomme poll-runder.** Hjerteslaget betyr «loopen kjører», ikke «det kommer meldinger». En stille topic skal ikke se død ut.
- **En kræsjet eller avsluttet loop må slutte å oppdatere hjerteslaget.** Fanger du og fortsetter på hver exception, tikker hjerteslaget videre og liveness slår aldri inn. Da mister sjekken hensikten.
- **Terskel større enn poll-frekvens pluss maks prosesseringstid.** Ellers trigger en treg, men sunn batch en falsk restart. Rundt 5 minutter er en trygg standard for topics med lite volum.
- **Aldri koble liveness til at brokeren er tilgjengelig.** Et kortvarig Kafka-avbrudd ville da restarte alle poder samtidig og gjøre et blaff om til et utfall.
- **Aldri koble liveness til consumer-lag.** Lag hører til metrikker og alarmer. Å restarte en pod som ligger etter, gjør laget verre.

## Implementasjonsnotater (for når en consumer kommer)

- Tilstandsholderen bruker `AtomicReference<Instant>`: consumer-coroutinen skriver, HTTP-handleren leser. Ingen lås trengs.
- Injiser en `Clock` slik at terskelen kan enhetstestes med en fake klokke (Kotest, uten Testcontainers).
- Hold liveness-tilstanden som en ren enhet uten I/O, og koble den inn i `is_alive`-ruten.

## Antimønstre å unngå

- Ping mot brokeren i `is_ready` eller `is_alive`.
- Consumeren lagt inn i readiness-sjekken.
- Heartbeat som tikker videre etter at loopen er død.
- Consumer-lag behandlet som liveness-feil.
