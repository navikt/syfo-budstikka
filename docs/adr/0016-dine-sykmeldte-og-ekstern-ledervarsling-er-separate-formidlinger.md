# ADR 0016 — Dine Sykmeldte-varsel og ekstern varsling til leder er separate formidlinger

- Status: Besluttet
- Dato: 2026-07-17

En `LEDERVARSEL`-formidling oppretter et aktivitetsvarsel i Dine Sykmeldte og
har derfor ikke `externalVarsling`. Ekstern varsling til nærmeste leder er en
egen `ARBEIDSGIVERVARSEL`-formidling med `NarmesteLeder` som mottaker. Dette
betyr ikke at ledere bare kan varsles i Dine Sykmeldte; det avgrenser hva én
`LEDERVARSEL`-formidling gjør.

Vi valgte to eksplisitte formidlinger fremfor en skjult dobbelteffekt fordi
kontrakten da uttrykker hva hver formidling faktisk kan gjøre. En produsent som
trenger både Dine Sykmeldte-varsel og ekstern varsling til lederen, må sende to
formidlinger.
