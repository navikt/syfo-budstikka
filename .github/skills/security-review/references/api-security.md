# API-sikkerhet — NAV-signal

Generisk API-sikkerhet (CORS-oppsett, CSP/X-Frame-Options/HSTS, Ktor `Authentication`/`CORS`-plugin-boilerplate, rate-limit-filterkode, cookie `Secure/HttpOnly/SameSite`, session fixation, CSRF-teori) er utenfor scope — LLM-en kan dette, og auth-oppsettet i koden dekkes av `/auth-overview`. Denne referansen dekker kun NAV-spesifikk signal.

## Sporbarhet med Nav-Call-Id

`Nav-Call-Id` må propageres gjennom hele kjeden. I Ktor settes den ved inngang via `CallId`-pluginen, legges i MDC for strukturert logging, og sendes videre på alle downstream-kall.

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)               // eller "Nav-Call-Id"
    generate { UUID.randomUUID().toString() }
    verify { it.isNotBlank() }
}
install(CallLogging) {
    callIdMdc("callId")                          // tilgjengelig i alle logglinjer
}
```

I klientkall til andre NAV-tjenester: sett `Nav-Call-Id` fra MDC på request, ikke generer en ny. Headeren brukes for korrelasjon, audit og feilsøking på tvers av tjenester. Den er ikke koblet til `accessPolicy`, som er NAIS sin nettverkskontroll, og brukes *aldri* som autorisasjonsgrunnlag.

## Nav-Consumer-Id for rate limiting og audit

Ved rate limiting mot interne konsumenter: bruk `Nav-Consumer-Id` som nøkkel før du faller tilbake på IP. Det gir meningsfull begrensning per konsument-app, ikke per NAIS-pod.

## accessPolicy er primærmekanismen

CORS, IP-allowlisting og egenvalidering av `Origin` er sekundære. Det primære nettverksforsvaret på NAIS-plattformen er `accessPolicy.inbound/outbound`. Se SKILL.md-seksjonen "accessPolicy som first-line defense".

For frontend-tjenester (Wonderwall foran): CSRF-beskyttelse og cookie-innstillinger håndteres normalt av Wonderwall/ingress-laget. Sjekk at Ktor-appen ikke dobbel-autentiserer eller overstyrer disse.

## Utvalgte OWASP API Top 10:2023-signaler for NAV

Bruk tabellen som en rask sjekk ved review. Den viser et utvalg av signaler og erstatter ikke NAV-vurderingene i SKILL.md eller trusselmodellen.

| OWASP API | Typisk NAV-signal | Sjekk i praksis |
|-----------|-------------------|-----------------|
| API1 Broken Object Level Authorization | Bruker eller ansatt kan slå opp ressurs med ID de ikke eier | Verifiser eierskap/sakstilhørighet i route-handleren, ikke bare at tokenet er gyldig |
| API2 Broken Authentication | Feil issuer/audience eller manglende `azp`-sjekk | Sjekk JWT-validering i `authenticate(...)`-blokken, pre-authorized apps og riktig auth-mekanisme |
| API3 Broken Object Property Level Authorization | API returnerer eller aksepterer felter klienten ikke skal se eller styre | Bruk eksplisitte DTO-er, ikke eksponer interne felter eller masseoppdatering ukritisk |
| API4 Unrestricted Resource Consumption | Kostbare kall kan spammes eller tømme CPU/minne | Sjekk paginering, payload-grenser, rate limiting og dyre business-flyter |
| API5 Broken Function Level Authorization | Vanlige brukere når admin- eller saksbehandlerfunksjoner | Sjekk rollegrenser (`claims.groups`), gruppesjekker og egne grener for kode 6/7 og egen ansatt |
| API7 SSRF | API henter videre URL eller host fra input | Begrens outbound med `accessPolicy`, whitelist hoster og valider destinasjon |
| API8 Security Misconfiguration | Åpen ingress, feil `accessPolicy`, debug-endepunkt eller feil CORS | Sjekk manifest, ingress, interne endepunkter og at Wonderwall/NAIS ikke overstyres |
| API10 Unsafe Consumption of APIs | Tredjeparts-API stoles på mer enn eget input | Valider svar, timeouts, retry-strategi og dataminimering mot eksterne kall |
