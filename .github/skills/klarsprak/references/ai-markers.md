---
description: "Identifies AI-generated patterns in Norwegian copy: inflated language, em-dash rhythm, false symmetry, and Nynorsk or Swedish leakage. Read when editing Norwegian text for natural Bokmål."
---
# AI markers — detailed reference

Patterns that reveal AI-generated text. Remove or vary them.

## Inflated words and phrases

| AI marker | Do this instead |
|-----------|---------------|
| «banebrytende», «revolusjonerende», «innovativ» | Use concrete descriptions |
| «representerer et betydelig skritt fremover» | State what it actually does |
| «robust», «helhetlig», «sømløs», «holistisk» | Rewrite or remove |
| «spiller en avgjørende rolle» | Get to the point |
| «dette understreker behovet for» | State the need directly |
| «effektivisere prosessen» | Name the process and how it changes |
| «sette brukeren i sentrum» | Explain what the service actually does |
| «muliggjør», «tilrettelegger for» | State what happens |

## English AI words that leak into Norwegian

These occur much more often in AI-generated Norwegian than in natural Norwegian.

- **delve into** → «fordype seg i» (overused; state the content instead)
- **leverage** → «utnytte», «bruke» (not «leverere»)
- **realm** → «område», «felt» (not «rike» or «sfære»)
- **underscore** → «understreke» (overused; state the point directly)
- **crucial** → «avgjørende» (overused; state why)
- **landscape** → «landskap» (overused metaphor; use «markedet», «feltet», or «situasjonen»)
- **foster** → «fremme» (overused; state the concrete action)
- **navigate** → «navigere» (overused metaphor; use «håndtere» or «forholde seg til»)
- **streamline** → «effektivisere» (overused; state what becomes easier)

## Opening and closing phrases: cut them

- «det er verdt å merke seg», «det er viktig å påpeke»
- «i dagens verden», «i en verden der»
- «la oss utforske», «la oss dykke ned i»
- «oppsummert kan man si at», «kort sagt», «avslutningsvis»
- «det finnes flere aspekter ved dette»
- «det bør nevnes at», «husk at»

## Punctuation as AI markers

- **An em dash (—) in every other bullet** is a clear AI marker. Use it rarely;
  vary with colons, parentheses, or rewriting.
- **A colon in every heading** (`Deploy: slik gjør vi det`) is an AI pattern.
  Vary heading forms.
- **Overused semicolons** are rarely natural in Norwegian technical text. Split
  the sentence or use a full stop.
- **Exclamation marks in technical text**: remove them.

## Structural patterns

- Remove summary sentences that only repeat preceding text.
- Do not force balance between alternatives when one is better («begge har sine fordeler»).
- Vary grammatical structure in bullets; identical form is an AI marker.
- Watch lists with identical rhythm in every line: same length, opening word,
  and conclusion all create an AI feel.
- Do not uncritically use three-step templates («først ..., deretter ..., til slutt ...»)
  when they flatten the content.
- Be sceptical of paragraphs following definition → benefit → summary without
  new information between.
- Remove false symmetry such as «på den ene siden / på den andre siden» when the
  text argues for one solution.
- Do not define what the reader already knows.
- Do not repeat a point in different words immediately after stating it.
- Remove «Derfor er X så viktig» when it only justifies the previous sentence.
- Do not overexplain the obvious.

## Transitional words

- Use «Videre», «Dessuten», and «I tillegg» rarely as paragraph openers.
- Replace «I lys av dette» and «Når det gjelder» by getting to the point.
- Never use «Furthermore», «Moreover», or «Additionally» in Norwegian text.

Prefer concrete subjects: «Tjenesten ...», «Koden ...», «Consumeren ...».

## Nynorsk and Swedish leakage

Language models mix Bokmål, Nynorsk, and Swedish. This often happens in
otherwise good text, so look for small patterns, not only obvious words.

### Common Nynorsk markers in Bokmål text

- `-ingar` → `-inger`
- `-leg` / `-lege` → `-lig` / `-lige`
- `kv-` → `hv-`
- `ei-` / `eig-` → `e-` / `eg-`
- `medan` → `mens`
- `vorte` / `vart` → `blitt` / `ble`
- `berre` → `bare`
- `mykje` → `mye`
- `difor` → `derfor`

### Common Swedish markers in Bokmål text

- `engångs-` → `engangs-`
- `ändring` → `endring`
- `åtgärd` → `tiltak`
- `möjlig` / `möjlighet` → `mulig` / `mulighet`
- `säker` / `säkerhet` brukt i svensk bøying → `sikker` / `sikkerhet`

### Important nuance

A endings such as `oppdaga`, `fila`, and `sida` can be valid, informal Bokmål if
the rest of the text is consistent. They are not the same as clear Nynorsk forms
such as `oppdateringar` or `mogleg`.
