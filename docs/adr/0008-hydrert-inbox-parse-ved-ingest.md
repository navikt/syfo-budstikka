# ADR 0008 — Hydrert inbox og parse ved ingest

- Status: Besluttet
- Dato: 2026-07-21

Ingest parser hele `Dispatch` og lagrer `reference` og `content` strukturert på
inbox-raden. `eventId` finnes bare i den obligatoriske Kafka-headeren og brukes
som dedupliseringsnøkkel. Syntaktisk ugyldige meldinger går til dead letter;
transiente lagringsfeil skal ikke committe offset.

Vi valgte dette fordi ventende opprettelser må kunne finnes og lukkes uten å
deserialisere en rå payload i flere flyter. Alternativet med parse-fri inbox
reduserte skjemakoblingen ved ingest, men flyttet kompleksitet til matching og
senere behandling.

Konsekvensen er at kontraktbrudd oppdages tidligere og kan kreve manuell replay
etter en parseroppgradering. Ukjente additive felt beholdes ikke i inbox; den
publiserte kontrakten er autoritativ for data budstikka trenger.

<a id="åpen-oppfølging-hold-plassering"></a>
Hold-plassering er et eget valg og er besluttet i [ADR 0014](0014-inbox-hold-for-sendevindu.md).
