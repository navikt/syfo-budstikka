#!/usr/bin/env bash
# Validate the complete custom-agent frontmatter and repository CLI setting.
#
# Usage: scripts/validate-agent-models.sh [agents-dir]
# Exit: 0 = exact contract matches, 1 = missing, extra, or unsafe declaration
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"
REPO_ROOT="$(cd "$AGENT_DIR/../.." && pwd)"

EXPECTED_FILES="grill-inspektor.agent.md grillmester.agent.md kokk.agent.md"

fail=0
actual_files="$(find "$AGENT_DIR" -maxdepth 1 -type f -name '*.agent.md' -exec basename {} \; | LC_ALL=C sort | tr '\n' ' ' | sed 's/ $//')"
if [ "$actual_files" != "$EXPECTED_FILES" ]; then
  echo "INVALID agent set: '$actual_files' (expected: '$EXPECTED_FILES')"
  fail=1
fi

for role in grillmester kokk grill-inspektor; do
  f="$AGENT_DIR/${role}.agent.md"
  if [ ! -f "$f" ]; then
    echo "MISSING: $f"; fail=1; continue
  fi

  if [ "$(head -n 1 "$f")" != "---" ]; then
    echo "INVALID: $role frontmatter must start on line 1"
    fail=1
    continue
  fi

  if ! frontmatter="$(awk '
    NR == 1 { next }
    $0 == "---" { found=1; exit }
    { print }
    END { if (!found) exit 1 }
  ' "$f")"; then
    echo "INVALID: $role has no closing frontmatter delimiter"
    fail=1
    continue
  fi

  case "$role" in
    grillmester)
      expected_contract=$'name: grillmester\ndescription: "Use @grillmester for non-trivial work that benefits from clarified requirements, explicit design decisions, a bounded implementation slice, and evidence-backed review."\nmodel: "claude-opus-5"\nuser-invocable: true\ndisable-model-invocation: true\ntools:\n  - read\n  - search\n  - edit\n  - execute\n  - agent\n  - skill\n  - web\n  - ask_user'
      ;;
    kokk)
      expected_contract=$'name: kokk\ndescription: "Internal implementer for one complete, independently testable vertical slice supplied through a concise Kokk task brief."\nmodel: "gpt-5.6-terra"\nuser-invocable: false\ndisable-model-invocation: false\ntools:\n  - read\n  - search\n  - edit\n  - execute\n  - skill'
      ;;
    grill-inspektor)
      expected_contract=$'name: grill-inspektor\ndescription: "Internal independent reviewer for a complete task-scoped diff, its acceptance criteria, named decisions, and deterministic evidence."\nmodel: "claude-opus-5"\nuser-invocable: false\ndisable-model-invocation: false\ntools:\n  - view\n  - grep\n  - glob'
      ;;
  esac

  if [ "$frontmatter" != "$expected_contract" ]; then
    echo "INVALID: $role frontmatter contract differs from the expected profile"
    fail=1
  fi

  if awk 'BEGIN { delimiters=0 } $0 == "---" { delimiters++; next } delimiters >= 2 { print }' "$f" |
    grep -Eiq '(claude|gpt|gemini|grok|kimi|mai)-[0-9a-z.]'; then
    echo "INVALID: $role body contains a model identifier; keep it in frontmatter only"
    fail=1
  fi

  echo "OK   $role"
done

settings="$REPO_ROOT/.github/copilot/settings.json"
if [ ! -f "$settings" ]; then
  echo "MISSING: $settings"
  fail=1
elif [ "$(tr -d '[:space:]' < "$settings")" != '{"includeCoAuthoredBy":false}' ]; then
  echo "INVALID: $settings must set only includeCoAuthoredBy=false"
  fail=1
else
  echo "OK   repository setting: includeCoAuthoredBy=false"
fi

exit $fail
