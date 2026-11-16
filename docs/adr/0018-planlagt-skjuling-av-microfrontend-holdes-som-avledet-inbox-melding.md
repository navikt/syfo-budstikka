# ADR 0018 — Planlagt skjuling av microfrontend holdes som avledet inbox-melding

- Status: Besluttet
- Dato: 2026-09-28

`MicrofrontendEnable.visibleUntil` betyr at Budstikka sender en disable på det
tidspunktet. Når enable besluttes, skrives en planlagt disable som egen
inbox-melding i `WAIT` til `visibleUntil`, og den vanlige oppvåkningen gjør den
til en leveranse. Når en nyere kommando per sykmeldt og microfrontend besluttes,
kansellerer den ventende planlagte disables fra eldre kommandoer i
ingest-rekkefølge. En passert `visibleUntil` behandles som umiddelbar disable.

Vi valgte inbox fremfor en fremtidsdatert leveranse fordi en leveranse da
fortsatt betyr en fryst utsending som skal skje, og fordi kansellering kan
bruke samme overgang som FERDIGSTILL uten en ny kansellert leveransetilstand
(ADR 0014). Vi valgte bort en synlighetstabell med egen forlengelsesoperasjon,
slik esyfovarsel har, fordi den gir en ny kontraktoperasjon og en tilstand som
konkurrerer med TMS. Kostnaden er at ikke-terminale inbox-meldinger må unntas
fra ordinær sletting, og at beslutninger for samme nøkkel må serialiseres.

Kanselleringen gjelder bare planer som ennå ikke er blitt leveranser.
Rekkefølgen mellom leveranser, også ved retry, følges i
[#315](https://github.com/navikt/syfo-budstikka/issues/315). En plan kan ligge
høyst 335 dager etter mottak, slik at den sendes før oppbevaringsjobben sletter
alle inbox-meldinger etter 365 dager, uansett tilstand. Ligger `visibleUntil`
senere, planlegges disable ved grensen.
