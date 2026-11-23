# TryggNok field contract

Load this reference when drafting or revising a TryggNok ROS. It defines the
copy-ready structure; it does not define NAV's scoring matrix or approve risk.

## Beskrivelse

Use these headings in this order:

```text
RISIKOVURDERINGENS FORMÅL

SYSTEMETS FORMÅL

SYSTEMBESKRIVELSE

OMFANG/AVGRENSNING
```

Cover the following content without duplicating it between headings:

- `RISIKOVURDERINGENS FORMÅL`: why the assessment is being performed, the
  assessed lifecycle stage or change, and the decision it supports.
- `SYSTEMETS FORMÅL`: the user or business outcome the system enables.
- `SYSTEMBESKRIVELSE`: actors, flow, integrations, runtime, storage, relevant
  data categories, access, retention, monitoring, and recovery controls.
- `OMFANG/AVGRENSNING`: environments, functions, actors, data flows, and
  producer or downstream responsibilities included and excluded.

## Oppsummering av risikovurderingen

Summarize the largest current residual risks, why they matter, and the measures
that most affect them. Include known incidents only when evidence or the user
establishes them. End with a conclusion that distinguishes:

- whether unacceptable residual risk has been identified;
- which measures or validations remain;
- who still needs to accept the residual risk.

Do not use stock claims such as "grundig risikovurdering", "nødvendige tiltak er
iverksatt", or "ingen uakseptabel restrisiko" unless the assessment evidence and
authorized owner support them.

## Risikoliste

Provide one entry per risk with these fields:

```text
Tema:
Risikohendelse:
Konsekvenser og kommentarer:
Sannsynlighet:
Konsekvens:
Risikonivå:
Tiltak:
```

For `Tema`, propose a concise TryggNok-compatible category and flag it for
mapping when the configured choices are unknown.

Write `Risikohendelse` as a specific possible event. Put causes, affected
people or processes, impact, relevant conditions, and uncertainty under
`Konsekvenser og kommentarer`. Avoid combining independent events merely
because they share a theme.

Use the scale and matrix configured in the assessment. If they have not been
provided, write `Foreslått: <score> – må valideres` and `Må beregnes i
TryggNok` for the color rather than asserting a matrix result.

End `Konsekvenser og kommentarer` with `Eksisterende kontroller:` and a concise
list of controls that exist today. TryggNok has no separate control column, so
keep this information in the comments field.

Each measure has:

```text
- Detaljer på tiltaket: <short measure name>
  Status: <valid status>
  Tiltaksbeskrivelse: <optional explanation, owner or verification condition>
```

Valid statuses are exactly:

- `Mulig tiltak`
- `Skal gjennomføres`
- `Implementert`
- `Besluttet å ikke gjennomføre`

Use `Implementert` only for a control that is present and verified. Use
`Besluttet å ikke gjennomføre` only when the accountable owner has made that
decision. In an initial draft, new recommendations normally remain `Mulig
tiltak` until the team commits to them.

## Review notes outside TryggNok

After the three copy-ready sections, return:

```text
Forutsetninger som er brukt
Åpne spørsmål før godkjenning
Viktigste kilder i kode og dokumentasjon
```

Keep these notes outside the text intended for TryggNok. They preserve the seam
between repository evidence, proposed scoring, and accountable human approval.
