# ADR 0001 — Domeneblind varselruter

- Status: Besluttet
- Dato: 2026-06-29

`syfo-budstikka` skal formidle varsler og andre flater uten å kjenne
produsentens fagdomene. Produsenten eier hva som skal formidles, hvilken
mottakerrelasjon det gjelder og når; budstikka eier kanaltilpasning,
leveringsrobusthet og felles regler for om formidling kan skje.

Vi valgte denne grensen for å unngå at én sentral tjeneste samler tekstkataloger,
fristregler og livssykluser fra mange fagområder. Konsekvensen er at produsentene
må sende ferdig innhold og eksplisitte kanalvalg, mens budstikka ikke kan
kompensere for manglende domenekunnskap.
