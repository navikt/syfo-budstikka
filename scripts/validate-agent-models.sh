#!/usr/bin/env bash
# Validate required agent model pins and the Inspector's read-only boundary.
#
# Usage: scripts/validate-agent-models.sh [agents-dir]
# Exit: 0 = required declarations are valid, 1 = missing or unsafe declaration
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"
fail=0

declare -A EXPECTED_MODELS=(
  [grillmester]="claude-opus-5"
  [kokk]="gpt-5.6-terra"
  [grill-inspektor]="claude-opus-5"
)

frontmatter_for() {
  awk 'NR == 1 { next } $0 == "---" { found=1; exit } { print }
    END { if (!found) exit 1 }' "$1"
}

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

  if ! frontmatter="$(frontmatter_for "$f")"; then
    echo "INVALID: $role has no closing frontmatter delimiter"
    fail=1
    continue
  fi

  model_count="$(grep -Ec '^model:' <<< "$frontmatter" || true)"
  model="$(sed -nE 's/^model:[[:space:]]*"?([^"#]+)"?[[:space:]]*$/\1/p' <<< "$frontmatter")"
  if [ "$model_count" -ne 1 ] || [ "$model" != "${EXPECTED_MODELS[$role]}" ]; then
    echo "INVALID: $role must declare model ${EXPECTED_MODELS[$role]} exactly once"
    fail=1
  fi

  if [ "$role" = "grill-inspektor" ]; then
    tools_count="$(grep -Ec '^tools:' <<< "$frontmatter" || true)"
    tool_set="$(awk '/^tools:/ { in_tools=1; next }
      in_tools && /^  - / { sub(/^  - /, ""); print; next }
      in_tools { exit }' <<< "$frontmatter" | LC_ALL=C sort)"
    if [ "$tools_count" -ne 1 ] || [ "$tool_set" != $'glob\ngrep\nview' ]; then
      echo "INVALID: $role tools must be exactly glob, grep, and view"
      fail=1
    fi
  fi

  if awk 'BEGIN { delimiters=0 } $0 == "---" { delimiters++; next } delimiters >= 2 { print }' "$f" |
    grep -Eiq '(claude-opus|gpt-[0-9])'; then
    echo "INVALID: $role body contains a model identifier; keep it in frontmatter only"
    fail=1
  fi

done

if [ "$fail" -eq 0 ]; then
  echo "OK   required agent semantics"
fi

exit $fail
