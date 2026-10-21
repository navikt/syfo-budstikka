# ADR 0018 — Planlagt skjuling av microfrontend holdes som avledet inbox-melding

- Status: Besluttet
- Dato: 2026-09-28

`MicrofrontendEnable.visibleUntil` betyr at Budstikka sender en disable på det
tidspunktet. Når enable besluttes, skrives en avledet inbox-melding med disable
i `WAIT` til `visibleUntil`, og den vanlige oppvåkningen gjør den til en
leveranse. Siste kommando per sykmeldt og microfrontend vinner etter
ingest-rekkefølge: nyere enable eller disable kansellerer ventende avledet
disable, og en passert `visibleUntil` behandles som umiddelbar disable.

Vi valgte inbox fremfor en fremtidsdatert leveranse fordi en leveranse da
fortsatt betyr en fryst utsending som skal skje, og fordi kansellering kan
bruke samme overgang som FERDIGSTILL uten en ny kansellert leveransetilstand
(ADR 0014). Vi valgte bort en synlighetstabell med egen forlengelsesoperasjon,
slik esyfovarsel har, fordi den gir en ny kontraktoperasjon og en tilstand som
konkurrerer med TMS. Kostnaden er at ikke-terminale inbox-meldinger må unntas
fra oppbevaringsjobben, og at beslutninger for samme nøkkel må serialiseres.

## Gjennomføring (#282)

Den avledede raden får `event_id` fra `UUID.nameUUIDFromBytes(kilde + "scheduled-disable")`,
samme `reference` og `received_at` som kilden, ventårsak `SCHEDULED_DISABLE` og den
nye kolonnen `source_event_id`. Kolonnen kom til under implementeringen. Uten den kan
vi ikke skille en avledet disable fra en produsentkommando, og ikke sortere den riktig
mot en kommando fra samme Kafka-batch, der `received_at` er lik.

Effektueringen tar først en advisory-lås per sykmeldt og microfrontend. Deretter låser
den kildens rad og ventende avledede rader fra eldre kommandoer i én spørring sortert på
`(received_at, event_id)`. Oppbevaringsjobben låser i samme rekkefølge og tar aldri
nøkkellåsen, så de to kan ikke vente på hverandre i ring.

- «Nyere kommando» betyr en nyere produsentkommando som allerede er besluttet. En
  nyere kommando som ennå ikke er besluttet, kansellerer den eldre planen når den selv
  besluttes. Feiler den, gjelder den eldre planen.
- Kanselleringen tar også en avledet rad som er vekket og claimet, men ikke besluttet.
  Workeren som holdt den, taper compare-and-set og sender ingenting.
- `visibleUntil` som er passert eller lik beslutningstidspunktet, gir en umiddelbar disable.
- `visibleUntil` kan ligge maks 335 dager etter kildens `received_at` (taket på 365 dager
  minus 30 dagers margin). Produsentfasaden avviser verdier utenfor grensen. Kommer en
  slik verdi likevel fram til beslutning, avviser vi den ikke. Vi planlegger disable ved
  grensen, logger en WARN uten personopplysninger og teller
  `inbox_scheduled_disable_capped_total`.

To kommandoer for samme nøkkel i samme Kafka-batch får lik `received_at`. Da avgjør
`event_id` rekkefølgen, og den er tilfeldig. Rekkefølgen mellom leveranser til TMS er
fortsatt #315.
