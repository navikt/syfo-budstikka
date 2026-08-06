#!/usr/bin/env bash
# Validate required agent model pins and capability boundaries.
#
# Usage: scripts/validate-agent-models.sh [agents-dir]
# Exit: 0 = required declarations are valid, 1 = missing or unsafe declaration
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"
fail=0

declare -A EXPECTED_MODELS=(
  [barista]="gpt-5.6-terra"
  [grillmester]="gpt-5.6-sol"
  [kokk]="gpt-5.6-terra"
  [grill-inspektor]="claude-opus-5"
)

declare -A EXPECTED_TOOLS=(
  [barista]=$'agent\nask_user\nedit\nexecute\nread\nsearch\nskill\nweb'
  [grillmester]=$'agent\nask_user\nedit\nexecute\nread\nsearch\nskill\nweb'
  [kokk]=$'edit\nexecute\nread\nsearch\nskill'
  [grill-inspektor]=$'glob\ngrep\nview'
)

declare -A EXPECTED_USER_INVOCABLE=(
  [barista]="true"
  [grillmester]="true"
  [kokk]="false"
  [grill-inspektor]="false"
)

declare -A EXPECTED_MODEL_INVOCATION_DISABLED=(
  [barista]="true"
  [grillmester]="true"
  [kokk]="false"
  [grill-inspektor]="false"
)

frontmatter_for() {
  awk 'NR == 1 { next } $0 == "---" { found=1; exit } { print }
    END { if (!found) exit 1 }' "$1"
}

for role in barista grillmester kokk grill-inspektor; do
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

  if ! awk '
    /^[[:space:]]*$/ { next }
    /^[[:space:]]*#/ { next }
    /^(name|description|model|user-invocable|disable-model-invocation|tools):/ { next }
    /^  - [[:alnum:]_-]+([[:space:]]+#.*)?$/ { next }
    { exit 1 }
  ' <<< "$frontmatter"; then
    echo "INVALID: $role frontmatter must use canonical top-level fields and tool-list syntax"
    fail=1
  fi

  name_count="$(grep -Ec '^name:' <<< "$frontmatter" || true)"
  canonical_name_count="$(grep -Fxc "name: $role" <<< "$frontmatter" || true)"
  if [ "$name_count" -ne 1 ] || [ "$canonical_name_count" -ne 1 ]; then
    echo "INVALID: $role must declare its canonical name exactly once"
    fail=1
  fi

  description_count="$(grep -Ec '^description:' <<< "$frontmatter" || true)"
  if [ "$description_count" -ne 1 ] || ! grep -Eq '^description: "[^"[:cntrl:]]+"$' <<< "$frontmatter"; then
    echo "INVALID: $role must declare one non-empty quoted description"
    fail=1
  fi

  model_count="$(grep -Ec '^model:' <<< "$frontmatter" || true)"
  canonical_model_count="$(grep -Fxc "model: \"${EXPECTED_MODELS[$role]}\"" <<< "$frontmatter" || true)"
  if [ "$model_count" -ne 1 ] || [ "$canonical_model_count" -ne 1 ]; then
    echo "INVALID: $role must declare model ${EXPECTED_MODELS[$role]} exactly once"
    fail=1
  fi

  user_invocable_count="$(grep -Ec '^user-invocable:' <<< "$frontmatter" || true)"
  user_invocable="$(sed -nE 's/^user-invocable:[[:space:]]*(true|false)[[:space:]]*$/\1/p' <<< "$frontmatter")"
  if [ "$user_invocable_count" -ne 1 ] || [ "$user_invocable" != "${EXPECTED_USER_INVOCABLE[$role]}" ]; then
    echo "INVALID: $role has the wrong user-invocable boundary"
    fail=1
  fi

  model_invocation_count="$(grep -Ec '^disable-model-invocation:' <<< "$frontmatter" || true)"
  model_invocation_disabled="$(sed -nE 's/^disable-model-invocation:[[:space:]]*(true|false)[[:space:]]*$/\1/p' <<< "$frontmatter")"
  if [ "$model_invocation_count" -ne 1 ] || [ "$model_invocation_disabled" != "${EXPECTED_MODEL_INVOCATION_DISABLED[$role]}" ]; then
    echo "INVALID: $role has the wrong model-invocation boundary"
    fail=1
  fi

  tools_count="$(grep -Ec '^tools:' <<< "$frontmatter" || true)"
  tool_set="$(awk '
    /^tools:[[:space:]]*$/ { in_tools=1; next }
    in_tools && /^[[:space:]]*$/ { next }
    in_tools && /^[[:space:]]*#/ { next }
    in_tools && /^  - / {
      item=$0
      sub(/^  - /, "", item)
      sub(/[[:space:]]+#.*$/, "", item)
      sub(/[[:space:]]+$/, "", item)
      if (item !~ /^[[:alnum:]_-]+$/) item="__INVALID_TOOL_SYNTAX__"
      print item
      next
    }
    in_tools { exit }
  ' <<< "$frontmatter" | LC_ALL=C sort)"
  if [ "$tools_count" -ne 1 ] || [ "$tool_set" != "${EXPECTED_TOOLS[$role]}" ]; then
    echo "INVALID: $role tools do not match its required capability boundary"
    fail=1
  fi

  body="$(awk 'BEGIN { delimiters=0 } $0 == "---" { delimiters++; next }
    delimiters >= 2 { print }' "$f")"
  if grep -Eiq '(claude-opus|gpt-[0-9])' <<< "$body"; then
    echo "INVALID: $role body contains a model identifier; keep it in frontmatter only"
    fail=1
  fi

done

if [ "$fail" -eq 0 ]; then
  echo "OK   required agent semantics"
fi

exit $fail
