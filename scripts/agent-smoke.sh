#!/usr/bin/env bash
# Smoke test for the Copilot setup. The default mode is deliberately static
# and free; --live makes one small Copilot call to check the front door.
#
# Usage: scripts/agent-smoke.sh [--live]
#
# --live CONSUMES COPILOT QUOTA. Use it only for an explicit environment check;
# the free static gate already enforces model policy.
set -euo pipefail

usage() {
  echo "Usage: scripts/agent-smoke.sh [--live]"
}

live=false
case "${1:-}" in
  "") ;;
  --live) live=true ;;
  -h|--help) usage; exit 0 ;;
  *) usage; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
  usage
  exit 2
fi

fail=0

static_check() { # name command...
  local name="$1"
  shift
  echo "— static: ${name}"
  if "$@"; then
    echo "  OK"
  else
    echo "  ERROR"
    fail=1
  fi
}

# This check must remain free. It proves the model policy without consuming
# Copilot quota.
static_check "agent model policy" \
  bash scripts/validate-agent-models.sh .github/agents

if [ "$live" != true ]; then
  echo "No Copilot calls made. Run with --live for the paid Barista integration check."
  exit "${fail}"
fi

if [ "$fail" -ne 0 ]; then
  echo "Live smoke aborted: a free static gate failed, so no Copilot quota was used."
  exit "${fail}"
fi

max_ai_credits="${GRILL_MAX_AI_CREDITS:-30}"
if ! [[ "$max_ai_credits" =~ ^[0-9]+$ ]]; then
  echo "ERROR: GRILL_MAX_AI_CREDITS must be an integer of at least 30 (was: $max_ai_credits)"
  exit 2
fi
if [ "$max_ai_credits" -lt 30 ]; then
  echo "ERROR: GRILL_MAX_AI_CREDITS must be at least 30 because that is the Copilot CLI minimum (was: $max_ai_credits)"
  exit 2
fi

echo "WARNING: --live makes exactly one Copilot prompt call. It has a soft limit of ${max_ai_credits} AI credits (--max-ai-credits)."
command -v copilot >/dev/null 2>&1 || { echo "ERROR: copilot CLI was not found in PATH"; exit 1; }
copilot_args=(
  --no-auto-update
  --no-color
  --disable-builtin-mcps
  --no-remote
  --no-remote-export
  --max-ai-credits "$max_ai_credits"
)

live_check() { # name agent model prompt expected-regex
  local name="$1" agent="$2" expected_model="$3" prompt="$4" expected="$5"
  local effective_model out response
  echo "— ${name}"
  if ! out=$(copilot "${copilot_args[@]}" --agent "${agent}" \
    --model "${expected_model}" --context default --output-format json \
    -p "${prompt}" 2>&1); then
    echo "  ERROR: call failed"; echo "${out}" | head -5; fail=1; return
  fi
  effective_model="$(python3 -c '
import json
import sys
models = []
for line in sys.stdin:
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        continue
    if event.get("type") == "assistant.message":
        model = event.get("data", {}).get("model")
        if model:
            models.append(model)
print(models[-1] if models else "")
' <<<"${out}")"
  response="$(python3 -c '
import json
import sys
messages = []
for line in sys.stdin:
    try:
        event = json.loads(line)
    except json.JSONDecodeError:
        continue
    if event.get("type") == "assistant.message":
        content = event.get("data", {}).get("content")
        if isinstance(content, str) and content.strip():
            messages.append(content)
print(messages[-1] if messages else "")
' <<<"${out}")"
  if [ "${effective_model}" != "${expected_model}" ]; then
    echo "  ERROR: expected effective model ${expected_model}, got ${effective_model:-<missing>}"
    fail=1
    return
  fi
  if grep -qiE "${expected}" <<<"${response}"; then
    echo "  OK"
  else
    echo "  ERROR: did not find /${expected}/ in the response:"
    echo "${response}" | head -15
    fail=1
  fi
}

# 1) Barista is the inexpensive, explicitly selected front door but must not
# own an architecture choice. The smoke test selects only user-invocable agents.
# shellcheck disable=SC2016 # The regex is literal; do not expand it in the shell.
live_check "barista escalation" barista gpt-5.6-terra \
  "We must choose a new domain model and migration strategy across several dependent steps. Which agent owns the next clarification? Reply with only the agent name." \
  '^[[:space:]]*([*`]{0,2})?@?grillmester([*`]{0,2})?[[:space:]]*$'

# Internal agents are not selected directly by this live front-door check.
exit ${fail}
