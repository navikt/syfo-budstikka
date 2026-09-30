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
