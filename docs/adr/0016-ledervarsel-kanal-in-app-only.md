# ADR 0016 — Ledervarsel er kun in-app

- Status: Besluttet
- Dato: 2026-07-17

`LEDERVARSEL` oppretter bare et aktivitetsvarsel i Dine Sykmeldte. Kontrakten har
derfor ikke `externalVarsling`, og sendevinduet er som standard løpende. Ekstern
varsling til nærmeste leder er en egen `ARBEIDSGIVERVARSEL`-formidling med
`NarmesteLeder` som mottaker.

Vi valgte to eksplisitte kanaler fremfor en skjult dobbelteffekt fordi kontrakten
da uttrykker hva hver kanal faktisk kan gjøre. En produsent som trenger både
in-app-varsel og e-post til lederen, må sende to formidlinger.
