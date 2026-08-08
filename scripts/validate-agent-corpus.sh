#!/usr/bin/env bash
# Deterministic gate for the agent corpus (.github/skills, .github/agents,
# .github/instructions). Enforces the invariants established when the corpus was
# pruned and migrated to English, so they cannot decay silently.
#
# Checks:
#   1. No Norwegian prose in agent-facing files, except a deliberate allowlist.
#   2. Every skill's `name:` matches its directory (the name is the skill id).
#   3. No reference to a skill or instruction file that does not exist.
#
# Usage: bash scripts/validate-agent-corpus.sh
# Exit:  0 = clean, 1 = violation
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

failures=0

fail() {
    printf '::error::%s\n' "$1" >&2
    failures=1
}

# --- 1. Norwegian prose -----------------------------------------------------
#
# Allowlisted, with the reason each one is deliberately Norwegian:
#   klarsprak/**                     Norwegian language craft is the subject
#   norwegian-text.instructions.md   same
#   triage/SKILL.md, AGENT-BRIEF.md  templates posted verbatim as Norwegian issue comments
#   readme-update/SKILL.md           names sections written into a Norwegian README
#   pull-request/SKILL.md            mirrors the Norwegian PR template
#   observability-setup/references/alerting.md   operator-facing alert text is Norwegian
allowlist_norwegian() {
    case "$1" in
        .github/skills/klarsprak/*) return 0 ;;
        .github/instructions/norwegian-text.instructions.md) return 0 ;;
        .github/skills/triage/SKILL.md | .github/skills/triage/AGENT-BRIEF.md) return 0 ;;
        .github/skills/readme-update/SKILL.md) return 0 ;;
        .github/skills/pull-request/SKILL.md) return 0 ;;
        .github/skills/observability-setup/references/alerting.md) return 0 ;;
        *) return 1 ;;
    esac
}

# Density rather than a single character: domain terms (Brukervarsel, sykmeldt,
# fødselsnummer) legitimately appear in English prose. Sustained Norwegian does not.
while IFS= read -r file; do
    # A staged deletion is still listed by git ls-files; nothing to read.
    [[ -f "$file" ]] || continue
    allowlist_norwegian "$file" && continue
    density="$(
        FILE="$file" python3 -c '
import os
import sys
from pathlib import Path

text = Path(os.environ["FILE"]).read_text(encoding="utf-8")
if not text:
    print(0)
    sys.exit()
print(round(sum(text.count(c) for c in "æøåÆØÅ") / len(text) * 1000, 1))
'
    )"
    over="$(python3 -c "print(1 if float('$density') > 3 else 0)")"
    if [[ "$over" == "1" ]]; then
        fail "Norwegian prose in agent-facing file (density ${density}/1000): $file. Translate it, or add it to the allowlist in this script with a reason."
    fi
done < <(git ls-files '.github/skills/**/*.md' '.github/agents/*.md' '.github/instructions/*.md')

# --- 2. Skill name matches its directory ------------------------------------
while IFS= read -r skill_file; do
    [[ -f "$skill_file" ]] || continue
    directory="$(basename "$(dirname "$skill_file")")"
    declared="$(
        FILE="$skill_file" python3 -c '
import os
import re
from pathlib import Path

text = Path(os.environ["FILE"]).read_text(encoding="utf-8")
match = re.search(r"^name:\s*(.+)$", text, re.M)
print(match.group(1).strip().strip("\"'"'"'") if match else "")
'
    )"
    if [[ -z "$declared" ]]; then
        fail "Skill has no name in its frontmatter: $skill_file"
    elif [[ "$declared" != "$directory" ]]; then
        fail "Skill name '${declared}' does not match its directory '${directory}': $skill_file. The name is the invocation id."
    fi
done < <(git ls-files '.github/skills/*/SKILL.md')

# --- 3. No references to skills or instruction files that do not exist -------
#
# Only names that were once skills are checked. A generic scan cannot work:
# `/metrics` and `/tmp` are ordinary paths, and `/kotlin` also occurs inside
# `src/main/kotlin`. Matching requires backticks, which is how skills are cited.
#
# Add a name here when a skill is retired, so a stale reference fails the build
# instead of silently pointing at nothing.
retired_skills=(
    api-design
    conventional-commit
    flyway-migration
    kotlin
    nav-architecture-review
    resolving-merge-conflicts
    unit-tests
)

existing_skills="$(git ls-files '.github/skills/*/SKILL.md' | awk -F/ '{print $3}' | sort -u)"

for retired in "${retired_skills[@]}"; do
    printf '%s\n' "$existing_skills" | grep -qx "$retired" && continue
    locations="$(git grep -ln -F "\`/${retired}\`" -- '.github' 'docs' || true)"
    [[ -n "$locations" ]] || continue
    fail "Reference to retired skill '/${retired}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
done

while IFS= read -r instruction_reference; do
    [[ -n "$instruction_reference" ]] || continue
    [[ -f "$instruction_reference" ]] && continue
    locations="$(git grep -ln -F "$instruction_reference" -- '.github' 'docs' || true)"
    fail "Reference to missing instruction file '${instruction_reference}' in: $(printf '%s' "$locations" | tr '\n' ' ')"
done < <(
    git grep -ohE '\.github/instructions/[a-z-]+\.instructions\.md' -- '.github' 'docs' | sort -u
)

# Links into a skill's own references/ directory must resolve. Scoped to that
# pattern on purpose: documentation templates contain illustrative paths like
# `./src/ordering/CONTEXT.md` that are examples, not links.
while IFS= read -r source_file; do
    [[ -f "$source_file" ]] || continue
    while IFS= read -r target; do
        [[ -n "$target" ]] || continue
        [[ -e "$(dirname "$source_file")/$target" ]] ||
            fail "Broken link to '${target}' in: $source_file"
    done < <(
        grep -oE '\]\(references/[a-zA-Z0-9_.-]+\.md\)' "$source_file" 2>/dev/null |
            sed -E 's/^\]\(//; s/\)$//' || true
    )
done < <(git ls-files '.github/skills/*/SKILL.md')

if [[ "$failures" -ne 0 ]]; then
    echo "Agent corpus validation failed." >&2
    exit 1
fi

echo "Agent corpus valid: no undeclared Norwegian, skill names match directories, no dangling references."
