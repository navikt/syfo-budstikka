# ADR 0003 — Application-lag for use case-orkestrering

- Status: Besluttet
- Dato: 2026-07-10

`application` er tjenestens use case-lag og kan avhenge av domenet og egne
porter, men ikke av `infrastructure` eller `bootstrap`. Konkrete workere som
orkestrerer domene og porter ligger her. Transportadaptere og generisk
worker-livssyklus ligger i `infrastructure`, mens `bootstrap` er composition
root.

Dette ble valgt fremfor å samle alle workere i infrastruktur, fordi den
varianten blander forretningsflyt med transport og livssyklus. Skillet gir et
ekstra lag, men gjør avhengighetsretningen og testgrensene tydelige.
