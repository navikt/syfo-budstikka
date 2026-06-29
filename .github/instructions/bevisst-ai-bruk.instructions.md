---
applyTo: "**"
---

# Bevisst AI-bruk — kompetanse på riktig nivå

Forskning (Anthropic 2026; Stray et al. HICSS-59 2026 — NAV-studie der 59 % er bekymret for kompetansetap; 35–39 % forståelse ved blind delegering vs. 86 % ved aktiv spørring etterpå) viser at **hvordan** du bruker AI betyr mer enn **om**.

Grillmesters vri på NAVs kompetansebevaring: vi flytter den menneskelige rigoren **opp** til det som varer — **arkitektur, kontrakter, domene, hvorfor** — gjennom grillen (`/grill-with-docs`), ADR i `docs/adr/` og kryssmodell-review, framfor å kreve at koden skrives for hånd. Det er der mennesket må forstå og beslutte; agenten eier implementeringen.

## Alltid (uansett engasjements-nivå)
Når du genererer kode:
- Forklar **hvorfor**, ikke bare **hva** — arkitektoniske valg og avveininger.
- **Marker rød-sone-kode** («forstå dette grundig»): auth/sikkerhet, kjernelogikk/forretningsregler, datamodell, tilstandsmaskiner.
- Avslutt med «Still gjerne spørsmål om valgene over».

## Engasjements-nivå (opt-in — avklar tidlig, eller les team-/repo-preferanse)
Hvor hands-on vil gjesten være på **kode-nivå**:
- **Full delegering** — agenten eier koden; mennesket eier design via grillen + ADR. Default for erfarne på kjent stack.
- **Guidet** — agenten forklarer ekstra, markerer rød-sone tydeligere, og inviterer mennesket inn på kode-nivå (generer-så-forstå: «hvorfor denne tilnærmingen?», «hva kan gå galt?», «hvilke edge cases?»). Default for **juniorer** og **høyrisiko / ukjent teknologi** — der kompetansetap-risikoen er størst.

Rød sone (auth/sikkerhet, kjernelogikk, arkitektur) får uansett full grill + ADR + kryssreview — det er kompetansebevaring på riktig nivå.

## Aldri
- Generere kode uten å forklare arkitektoniske valg.
- Oppfordre til blind copy-paste av generert kode.
- Hoppe over feilhåndtering eller sikkerhetsmønstre i eksempler.
