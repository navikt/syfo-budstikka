# Agent setup provenance

Hovmester is the team's upstream source for reusable agent contracts. The
checked-in contracts in this repository are a repository-local adaptation and
the sole operative contract here, not a runtime dependency or synchronized
installation.

`mattpocock/skills` and `navikt/copilot` are reviewed inputs. They do not
override local contracts. Fixed revisions are maintainer provenance only;
review concrete upstream diffs before porting them.

## Verified sources

Each revision below was resolved against the GitHub API on 2026-07-30. The
Matt Pocock and Hovmester pins were rechecked on 2026-07-31 and remained their
respective `main` heads. Record only revisions verified this way; an
unresolvable revision is not provenance.

| Source | Revision | Role |
|---|---|---|
| [`navikt/hovmester`](https://github.com/navikt/hovmester) | [`48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2`](https://github.com/navikt/hovmester/commit/48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2) | Team source for reusable agent contracts |
| [`mattpocock/skills`](https://github.com/mattpocock/skills) | [`2ab958093e83e0ec752e6c1c5932da465bf23e0c`](https://github.com/mattpocock/skills/commit/2ab958093e83e0ec752e6c1c5932da465bf23e0c) | MIT-licensed input |
| [`navikt/copilot`](https://github.com/navikt/copilot) | [`6bd76a064a5615ba8a4bef1e27017368c562012e`](https://github.com/navikt/copilot/commit/6bd76a064a5615ba8a4bef1e27017368c562012e) | MIT-licensed secondary input |

`mattpocock/skills` and `navikt/copilot` are MIT licensed. The complete Matt
Pocock notice applicable to imported material is preserved in
[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md);
the `navikt/copilot` license text remains in that repository because no
material from it is imported in this slice. `navikt/hovmester` declares no
repository license, so this repository asserts none for it and relies on
internal team ownership instead.

## Imported and adapted skill core

The following paths were reviewed against the pinned Matt Pocock revision and,
where shown, the pinned Hovmester revision. They were rechecked on 2026-07-31.
The repository-local files are the operative versions.

| Local path | Upstream path at the pinned revision | Local handling |
|---|---|---|
| `.github/skills/grilling/SKILL.md` | `skills/productivity/grilling/SKILL.md` | Imported without behavioral changes |
| `.github/skills/grill-me/SKILL.md` | `skills/productivity/grill-me/SKILL.md` | Imported as the manual thin wrapper |
| `.github/skills/grill-with-docs/SKILL.md` | `skills/engineering/grill-with-docs/SKILL.md` | Imported without behavioral changes |
| `.github/skills/domain-modeling/SKILL.md` | `skills/engineering/domain-modeling/SKILL.md` | Imported with a portable repository-policy seam; syfo paths and formats live in `docs/agents/domain.md` |
| `.github/skills/domain-modeling/ADR-FORMAT.md` | `skills/engineering/domain-modeling/ADR-FORMAT.md` | Retains Matt's minimal fallback, decision gate, and qualification list; repository policy takes precedence |
| `.github/skills/domain-modeling/CONTEXT-FORMAT.md` | `skills/engineering/domain-modeling/CONTEXT-FORMAT.md` | Imported as the English fallback; the local Norwegian glossary mapping lives in `docs/agents/domain.md` |
| `.github/skills/handoff/SKILL.md` | `skills/productivity/handoff/SKILL.md` | Adapted with OS-temporary storage, absolute-path output, and a bundled fail-closed repository-state fingerprint |
| `.github/skills/create-a-skill/SKILL.md` | `skills/productivity/writing-great-skills/SKILL.md`; Hovmester `dist/skills/create-a-skill/SKILL.md` | Reworked into one concise, action-oriented workflow under Hovmester's stable public name while preserving model and human reachability |
| `.github/skills/create-a-skill/references/principles.md` | `skills/productivity/writing-great-skills/SKILL.md` | Matt's English principles adapted into progressively disclosed, runtime-neutral reference |
| `.github/skills/create-a-skill/references/glossary.md` | `skills/productivity/writing-great-skills/GLOSSARY.md` | Complete term set retained in English and condensed for the local actionable skill |

The upstream `agents/openai.yaml` files are OpenAI interface metadata, not
GitHub Copilot CLI runtime dependencies, and are deliberately not imported.

## Import rule

When repository content is copied or substantially adapted, record the
concrete source paths and full source revision, and preserve every applicable
copyright and license notice. Move a recorded revision only in the same change
that adopts it; a pin is never advanced without the diff having been assessed.
The import table above is updated whenever one of these local adaptations
adopts a newer upstream revision or adds another upstream source path.
