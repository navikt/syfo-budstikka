#!/usr/bin/env bash
# Free CI gate for the four repository agent model pins.
set -euo pipefail

agent_dir="${1:-.github/agents}"
expected=(
  "barista:gpt-5.6-terra"
  "grillmester:claude-opus-5"
  "kokk:gpt-5.6-terra"
  "grill-inspektor:claude-opus-5"
)

fail=0
for entry in "${expected[@]}"; do
  role="${entry%%:*}"
  wanted="${entry#*:}"
  file="$agent_dir/$role.agent.md"

  if [ ! -f "$file" ]; then
    echo "MISSING  $file"
    fail=1
    continue
  fi

  model="$(sed -nE 's/^model:[[:space:]]*"?([^"#]+)"?[[:space:]]*$/\1/p' "$file" | xargs)"
  if [ "$model" = "$wanted" ]; then
    echo "OK       $role: $model"
  else
    echo "INVALID  $role: ${model:-<missing>} (expected $wanted)"
    fail=1
  fi
done

exit "$fail"
