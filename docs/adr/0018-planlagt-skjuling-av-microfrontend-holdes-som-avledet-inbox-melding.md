# ADR 0018 — Planlagt skjuling av microfrontend holdes som avledet inbox-melding

- Status: Besluttet
- Dato: 2026-09-28

`MicrofrontendEnable.visibleUntil` betyr at Budstikka planlegger en disable som
behandles på eller etter tidspunktet. Når enable besluttes, skrives den som egen
inbox-melding i `WAIT` til `visibleUntil`, og den vanlige oppvåkningen gjør den
til en leveranse. Når en nyere enable eller disable for samme sykmeldt og microfrontend
besluttes, kansellerer den ventende planlagte disables fra eldre meldinger i
ingest-rekkefølge. En passert `visibleUntil` behandles som umiddelbar disable.

Vi valgte inbox fremfor en fremtidsdatert leveranse fordi en leveranse da
fortsatt betyr en fryst utsending som skal skje, og fordi kansellering kan
bruke samme overgang som FERDIGSTILL uten en ny kansellert leveransetilstand
(ADR 0014). Vi valgte bort en synlighetstabell med egen forlengelsesoperasjon,
slik esyfovarsel har, fordi den gir en ny kontraktoperasjon og en tilstand som
konkurrerer med TMS. Kostnaden er at ikke-terminale inbox-meldinger og enable-meldingene
til ventende planlagte disables må unntas fra ordinær sletting. Nyere, ferdigbehandlede
meldinger beholdes også mens en eldre enable for samme nøkkel fortsatt kan opprette en plan;
ellers mister den eldre enable-meldingen beviset på den nyere meldingen. Beslutninger for samme
nøkkel må serialiseres. Den absolutte oppbevaringsgrensen gjelder fortsatt alle radene.

Kanselleringen gjelder bare planlagte meldinger som ennå ikke er blitt leveranser.
Rekkefølgen mellom leveranser, også ved retry, følges i
[#315](https://github.com/navikt/syfo-budstikka/issues/315). Tidspunktet for planlagt
skjuling settes høyst 335 dager etter mottak. Det gir 30 dagers margin før
oppbevaringsjobben sletter alle inbox-meldinger etter 365 dager, uansett tilstand,
men er ingen garanti for levering innen grensen. Ligger `visibleUntil` senere,
planlegges disable ved 335-dagersgrensen.
