# Agent setup provenance

Hovmester is the team's upstream source for reusable agent contracts. The
checked-in contracts in this repository are a repository-local adaptation and
the sole operative contract here, not a runtime dependency or synchronized
installation.

`mattpocock/skills` and `navikt/copilot` are reviewed inputs. They do not
override local contracts. Fixed revisions are maintainer provenance only;
review concrete upstream diffs before porting them.

## Verified sources

Each revision below was resolved against the GitHub API on 2026-07-30 and is
the referenced repository's `main` head at that time. Record only revisions
verified this way; an unresolvable revision is not provenance.

| Source | Revision | Role |
|---|---|---|
| [`navikt/hovmester`](https://github.com/navikt/hovmester) | [`48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2`](https://github.com/navikt/hovmester/commit/48483bf32c2b6f89c31e7d50e25b5fe6fac45ca2) | Team source for reusable agent contracts |
| [`mattpocock/skills`](https://github.com/mattpocock/skills) | [`2ab958093e83e0ec752e6c1c5932da465bf23e0c`](https://github.com/mattpocock/skills/commit/2ab958093e83e0ec752e6c1c5932da465bf23e0c) | MIT-licensed input |
| [`navikt/copilot`](https://github.com/navikt/copilot) | [`6bd76a064a5615ba8a4bef1e27017368c562012e`](https://github.com/navikt/copilot/commit/6bd76a064a5615ba8a4bef1e27017368c562012e) | MIT-licensed secondary input |

`mattpocock/skills` and `navikt/copilot` are MIT licensed; their license texts
live in those repositories. `navikt/hovmester` declares no repository license,
so this repository asserts none for it and relies on internal team ownership
instead.

## Import rule

When repository content is copied or substantially adapted, record the
concrete source paths and full source revision, and preserve every applicable
copyright and license notice. Move a recorded revision only in the same change
that adopts it; a pin is never advanced without the diff having been assessed.
This foundation slice imports no third-party skill or instruction text; license
notices belong to the slice that imports that material.
