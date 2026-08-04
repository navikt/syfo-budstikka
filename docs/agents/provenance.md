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

## Locally adapted workflow agents

The following repository agents were reviewed against the pinned Hovmester
revision. They preserve the useful role boundaries while keeping the existing
Grillmester phase loop and repository policy. The local files are the operative
contracts.

| Local path | Hovmester path at the pinned revision | Local handling |
|---|---|---|
| `.github/agents/barista.agent.md` | `dist/agents/barista.agent.md` | Adapted as a compact English solo-first entry point: it owns ordinary implementation, routes unresolved decisions and high risk to user-selected Grillmester, and invokes only an explicitly selected Grill-inspektor review |
| `.github/agents/grillmester.agent.md` | `dist/agents/hovmester.agent.md` | Adapted to preserve Grillmester's phase loop, natural grilling, R0/R1 fast path, phase anchor, and end-to-end ownership while delegating one Kokk slice at a time |
| `.github/agents/kokk.agent.md` | `dist/agents/kokk.agent.md` | Adapted to one concise task brief, one vertical slice, five explicit completion statuses, and a commit-free handoff to the orchestrator |
| `.github/agents/grill-inspektor.agent.md` | `dist/agents/inspektor-claude.agent.md` | Adapted to a compact independent review contract with explicit `view`, `grep`, and `glob` tools, no write or shell boundary, and no mandatory positive section |

The capability declarations follow GitHub's documented
[custom-agent tool names and aliases](https://docs.github.com/en/copilot/reference/custom-agents-configuration#tools).
A bounded repository pilot on 2026-08-03 exercised Kokk's read, search, and
command paths and Inspector's read-only paths.
`scripts/validate-agent-models.sh` pins the declared capability and reachability
boundaries for all four roles so they cannot drift silently.

## Imported and adapted skill core

The following paths were reviewed against the pinned Matt Pocock revision and,
where shown, the pinned Hovmester revision. They were rechecked on 2026-07-31.
The repository-local files are the operative versions.

| Local path | Upstream path at the pinned revision | Local handling |
|---|---|---|
| `.github/skills/grilling/SKILL.md` | `skills/productivity/grilling/SKILL.md` | Imported without behavioral changes |
| `.github/skills/grill-me/SKILL.md` | `skills/productivity/grill-me/SKILL.md` | Imported as the manual thin wrapper |
| `.github/skills/grill-with-docs/SKILL.md` | `skills/engineering/grill-with-docs/SKILL.md` | Imported without behavioral changes |
| `.github/skills/domain-modeling/SKILL.md` | `skills/engineering/domain-modeling/SKILL.md` | Imported with a portable repository-policy seam and an explicit durable-write boundary; syfo paths and formats live in `docs/agents/domain.md` |
| `.github/skills/domain-modeling/ADR-FORMAT.md` | `skills/engineering/domain-modeling/ADR-FORMAT.md` | Retains Matt's minimal fallback and qualification examples; `/domain-modeling` owns the decision gate and repository policy takes precedence |
| `.github/skills/domain-modeling/CONTEXT-FORMAT.md` | `skills/engineering/domain-modeling/CONTEXT-FORMAT.md` | Imported as the English fallback; the local Norwegian glossary mapping lives in `docs/agents/domain.md` |
| `.github/skills/handoff/SKILL.md` | `skills/productivity/handoff/SKILL.md` | Adapted with private OS-temporary storage, absolute-path output, verified-versus-unverified claims, and a lightweight branch/HEAD/status receiver preflight |
| `.github/skills/create-a-skill/SKILL.md` | `skills/productivity/writing-great-skills/SKILL.md`; Hovmester `dist/skills/create-a-skill/SKILL.md` | Reworked into one action-oriented GitHub Copilot CLI workflow under Hovmester's stable public name while preserving model and human reachability |
| `.github/skills/create-a-skill/references/principles.md` | `skills/productivity/writing-great-skills/SKILL.md` | Matt's English principles adapted into progressively disclosed authoring reference |
| `.github/skills/create-a-skill/references/glossary.md` | `skills/productivity/writing-great-skills/GLOSSARY.md` | Complete term set retained in English and condensed for the local actionable skill |
| `.github/skills/create-a-skill/references/copilot-cli-validation.md` | [GitHub Copilot CLI skills reference](https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-command-reference#skills-reference) | Local, progressively disclosed validation checklist for the repository's only target runtime |

The upstream `agents/openai.yaml` files are OpenAI interface metadata, not
GitHub Copilot CLI runtime dependencies, and are deliberately not imported.

## Reviewed upstream proposals

Open proposals are design input, not pinned operative contracts. The following
were reviewed on 2026-07-31 and adopted only in the narrow form recorded here:

| Proposal | Local handling |
|---|---|
| [`mattpocock/skills#299`](https://github.com/mattpocock/skills/issues/299) | Unresolved choices remain in the issue, plan, or decision map; a deliberately persisted proposal is status-marked, with no mandatory promotion or backlink protocol |
| [`mattpocock/skills#306`](https://github.com/mattpocock/skills/issues/306) | `/handoff` separates freshly verified state from unverified claims and asks the receiver to recheck branch, HEAD, and status; no fingerprint or persistence framework was adopted |

## Import rule

When repository content is copied or substantially adapted, record the
concrete source paths and full source revision, and preserve every applicable
copyright and license notice. Move a recorded revision only in the same change
that adopts it; a pin is never advanced without the diff having been assessed.
The import table above is updated whenever one of these local adaptations
adopts a newer upstream revision or adds another upstream source path.
