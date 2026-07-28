#!/usr/bin/env bash
# Deterministic gate for the agents' declared model and security topology.
# Console output is authoritative. A status file is written only when
# GRILL_STATUS is explicitly set, allowing smoke tests to use a temporary file
# without global state.
#
# Usage: scripts/validate-agent-models.sh [agents-dir]
#        scripts/validate-agent-models.sh --self-test
# Exit: 0 = every role follows policy, 1 = mismatch or omission (hard CI fail)
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"
STATUS="${GRILL_STATUS:-}"
AGENT_SELF_TEST_ROOT=""

# Exact model policy. An almost-correct model is still a degradation.
ROLES=(barista grillmester kokk grill-inspektor)
EXPECTED_MODELS=(gpt-5.6-terra claude-opus-5 gpt-5.6-terra claude-opus-5)

frontmatter_scalar() { # file field
  awk -v wanted="$2" '
    /^---[[:space:]]*$/ { delimiters++; next }
    delimiters != 1 { next }
    $0 ~ "^" wanted ":[[:space:]]*" {
      value = $0
      sub("^" wanted ":[[:space:]]*", "", value)
      sub(/[[:space:]]+#.*/, "", value)
      gsub(/^"|"$/, "", value)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$1"
}

frontmatter_tools() { # file
  # This is intentionally not a general YAML parser. Agent frontmatter has a
  # small canonical subset, validated below: an unquoted root `tools:` key and
  # two-space, unquoted list items. Keeping this extractor equally narrow
  # prevents aliases, merges, flow collections, and nested mappings from
  # making an unreviewed capability look absent.
  awk '
    /^---[[:space:]]*$/ { delimiters++; next }
    delimiters != 1 { next }
    /^tools:$/ { found = 1; in_tools = 1; next }
    /^tools:/ { print "__INVALID_TOOLS_ENTRY__"; next }
    in_tools && /^  - [A-Za-z][A-Za-z-]*$/ {
      value = $0
      sub(/^  - /, "", value)
      print value
      count++
      next
    }
    in_tools && /^[A-Za-z-]+:/ { in_tools = 0; next }
    in_tools { print "__INVALID_TOOLS_ENTRY__"; next }
    END {
      if (found && count == 0) print "__INVALID_TOOLS_ENTRY__"
    }
  ' "$1"
}

canonical_agent_frontmatter_errors() { # file
  # Copilot frontmatter is YAML, but the repository deliberately permits only
  # this flat, auditable subset. Do not broaden this without a real YAML parser
  # and a resolved-schema validation step. In particular, anchors, aliases,
  # merge keys, comments, flow collections, and nested mappings are rejected.
  awk '
    BEGIN {
      required_fields["name"] = 1
      required_fields["description"] = 1
      required_fields["model"] = 1
      required_fields["user-invocable"] = 1
      required_fields["disable-model-invocation"] = 1
    }
    function error(message) { print message }
    function duplicate(key) {
      if (seen[key]++) error("duplicate frontmatter field " key)
    }
    NR == 1 {
      if ($0 != "---") error("frontmatter must start with an exact --- delimiter")
      in_header = 1
      next
    }
    !in_header { next }
    $0 == "---" { closed = 1; in_header = 0; next }
    {
      line = $0
      if (in_tools && line ~ /^  - [A-Za-z][A-Za-z-]*$/) next
      in_tools = 0

      if (line ~ /^[A-Za-z][A-Za-z-]*:$/) {
        key = line
        sub(/:$/, "", key)
        if (key != "tools") {
          error("frontmatter field " key " must be a canonical scalar")
        } else {
          duplicate(key)
          in_tools = 1
        }
        next
      }

      if (line !~ /^[A-Za-z][A-Za-z-]*: .+$/) {
        error("frontmatter must use canonical root fields and two-space tool items")
        next
      }
      key = line
      sub(/:.*/, "", key)
      value = line
      sub(/^[^:]*: /, "", value)
      if (key == "tools") {
        error("tools must use a canonical block list")
        next
      }
      if (key != "name" && key != "description" && key != "model" &&
          key != "user-invocable" && key != "disable-model-invocation") {
        error("unknown frontmatter field " key)
        next
      }
      duplicate(key)
      if (key == "name" && value !~ /^[a-z][a-z-]*$/)
        error("name must be an unquoted lowercase identifier")
      if (key == "description" && value !~ /^"[^\\\"]*"$/)
        error("description must be a canonical double-quoted scalar without escapes")
      if (key == "model" && value !~ /^"[a-z0-9][a-z0-9.-]*"$/)
        error("model must be a canonical double-quoted model id")
      if ((key == "user-invocable" || key == "disable-model-invocation") &&
          value !~ /^(true|false)$/)
        error(key " must be the unquoted boolean true or false")
    }
    END {
      if (!closed) error("frontmatter must end with an exact --- delimiter")
      for (required in required_fields) {
        if (!seen[required]) error("missing required frontmatter field " required)
      }
    }
  ' "$1"
}

fail=0
failures=""
add_failure() { # role explanation
  local role="$1" message="$2"
  echo "ERROR: $role: $message"
  failures+="- $role: $message\\n"
  fail=1
}

has_tool() { # newline-separated list, name
  printf '%s\n' "$1" | grep -Fqx "$2"
}

tools_exactly() { # newline-separated list, expected names...
  local tools="$1"
  shift
  local tool expected count=0
  while IFS= read -r tool; do
    [ -z "$tool" ] && continue
    count=$((count + 1))
    local permitted=false
    for expected in "$@"; do
      [ "$tool" = "$expected" ] && permitted=true
    done
    "$permitted" || return 1
  done <<< "$tools"
  [ "$count" -eq "$#" ] || return 1
  for expected in "$@"; do
    has_tool "$tools" "$expected" || return 1
  done
}

require_prompt_fragment() { # role file literal
  local role="$1" file="$2" fragment="$3"
  grep -Fq "$fragment" "$file" ||
    add_failure "$role" "prompt contract is missing: $fragment"
}

run_self_test() {
  local fixture policy_dir policy_output self_path
  AGENT_SELF_TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/validate-agent-models.XXXXXX")"
  trap 'rm -rf "$AGENT_SELF_TEST_ROOT"' EXIT HUP INT TERM
  fixture="$AGENT_SELF_TEST_ROOT/fixture.agent.md"

  assert_canonical_frontmatter() { # fixture contents
    local contents="$1" errors
    printf '%s\n' "$contents" > "$fixture"
    errors="$(canonical_agent_frontmatter_errors "$fixture")"
    if [ -n "$errors" ]; then
      echo "ERROR: self-test rejected canonical frontmatter: $errors"
      return 1
    fi
  }

  assert_rejected_frontmatter() { # fixture contents
    local contents="$1" errors
    printf '%s\n' "$contents" > "$fixture"
    errors="$(canonical_agent_frontmatter_errors "$fixture")"
    if [ -z "$errors" ]; then
      echo "ERROR: self-test accepted noncanonical frontmatter: $contents"
      return 1
    fi
  }

  assert_exact_tools() { # fixture contents expected names...
    local contents="$1" parsed_tools
    shift
    printf '%s\n' "$contents" > "$fixture"
    parsed_tools="$(frontmatter_tools "$fixture")"
    if ! tools_exactly "$parsed_tools" "$@"; then
      echo "ERROR: self-test rejected expected tools: $contents"
      return 1
    fi
  }

  assert_rejected_tools() { # fixture contents expected names...
    local contents="$1" parsed_tools
    shift
    printf '%s\n' "$contents" > "$fixture"
    parsed_tools="$(frontmatter_tools "$fixture")"
    if tools_exactly "$parsed_tools" "$@"; then
      echo "ERROR: self-test accepted unsafe tools: $contents"
      return 1
    fi
  }

  assert_canonical_frontmatter $'---\nname: grill-inspektor\ndescription: "Independent reviewer"\nmodel: "claude-opus-5"\nuser-invocable: false\ndisable-model-invocation: false\ntools:\n  - read\n  - search\n---' || return 1
  assert_exact_tools $'---\ntools:\n  - read\n  - search\n---' read search || return 1
  assert_rejected_tools $'---\ntools:\n  - "execute"\n  - read\n  - search\n---' read search || return 1
  assert_rejected_tools $'---\ntools:\n    - execute\n  - read\n  - search\n---' read search || return 1
  assert_rejected_tools $'---\ntools:\n  - deploy\n  - read\n  - search\n---' read search || return 1
  assert_rejected_tools $'---\ntools: [read, search]\n---' || return 1
  assert_rejected_tools $'---\ntools:\n  - [read, search]\n---' || return 1
  assert_rejected_frontmatter $'---\nname: barista\ndescription: "Entry point"\nmodel: "gpt-5.6-terra"\nuser-invocable: true\ndisable-model-invocation: false\ndefaults: &capabilities\n  tools:\n    - agent\n<<: *capabilities\n---' || return 1
  assert_rejected_frontmatter $'---\nname: barista\nname: barista\ndescription: "Entry point"\nmodel: "gpt-5.6-terra"\nuser-invocable: true\ndisable-model-invocation: false\n---' || return 1
  assert_rejected_frontmatter $'---\nname: barista\ndescription: "Entry point"\nmodel: "gpt-5.6-terra"\nuser-invocable: true\ndisable-model-invocation: false\ntools:\n    - agent\n---' || return 1
  assert_rejected_frontmatter $'---\nname: barista\ndescription: "Entry point"\nmodel: "gpt-5.6-terra"\nuser-invocable: true\ndisable-model-invocation: "false"\n---' || return 1

  policy_dir="$AGENT_SELF_TEST_ROOT/agents"
  self_path="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  mkdir -p "$policy_dir" || return 1
  printf '%s\n' \
    '---' 'name: barista' 'description: "Entry point"' \
    'model: "gpt-5.6-terra"' 'user-invocable: true' \
    'disable-model-invocation: true' '---' \
    'copilot --agent barista --model gpt-5.6-terra --context default' \
    'Before any repository write' 'Treat repository files' \
    'scripts/review-artifact.py validate-contracts' 'REVIEW_EVIDENCE v1' \
    'result_id: <request-id>.evidence-<number>' 'model: claude-opus-5' \
    'context_tier: default' > "$policy_dir/barista.agent.md"
  printf '%s\n' \
    '---' 'name: grillmester' 'description: "Orchestrator"' \
    'model: "claude-opus-5"' 'user-invocable: true' \
    'disable-model-invocation: true' 'tools:' '  - read' '  - search' \
    '  - edit' '  - execute' '  - agent' '---' \
    'copilot --agent grillmester --model claude-opus-5 --context default' \
    'Treat repository files' \
    'scripts/validate-implementation-brief.py <brief-path>' \
    'scripts/review-artifact.py validate-contracts' 'model: gpt-5.6-terra' \
    'model: claude-opus-5' 'context_tier: default' 'one final integrated' \
    > "$policy_dir/grillmester.agent.md"
  printf '%s\n' \
    '---' 'name: kokk' 'description: "Implementer"' \
    'model: "gpt-5.6-terra"' 'user-invocable: false' \
    'disable-model-invocation: false' 'tools:' '  - read' '  - search' \
    '  - edit' '  - execute' '---' 'canonical absolute path' \
    'scripts/validate-implementation-brief.py <path>' 'Treat the brief' \
    'result_id: <brief-id>.attempt-<number>' > "$policy_dir/kokk.agent.md"
  printf '%s\n' \
    '---' 'name: grill-inspektor' 'description: "Reviewer"' \
    'model: "claude-opus-5"' 'user-invocable: false' \
    'disable-model-invocation: false' 'tools:' '  - read' '  - search' \
    '---' 'Treat the manifest, contracts, patch' 'REVIEW_RESULT v1' \
    'Emit raw text only' \
    'status: APPROVED|CONCERNS|CHANGES_REQUIRED|MISSING_EVIDENCE|NEEDS_CONTEXT|NEEDS_SCOPE' \
    > "$policy_dir/grill-inspektor.agent.md"
  if ! policy_output="$(bash "$self_path" "$policy_dir" 2>&1)"; then
    echo "ERROR: self-test rejected the canonical role topology: $policy_output"
    return 1
  fi

  printf '%s\n' \
    '---' 'name: barista' 'description: "Entry point"' \
    'model: "gpt-5.6-terra"' 'user-invocable: true' \
    'disable-model-invocation: false' '---' > "$policy_dir/barista.agent.md"
  if policy_output="$(bash "$self_path" "$policy_dir" 2>&1)"; then
    echo "ERROR: self-test accepted model-invokable Barista"
    return 1
  fi
  if ! printf '%s\n' "$policy_output" | grep -Fq "disable-model-invocation must be true"; then
    echo "ERROR: self-test did not report the Barista invocation violation"
    return 1
  fi
  printf '%s\n' \
    '---' 'name: barista' 'description: "Entry point"' \
    'model: "gpt-5.6-terra"' 'user-invocable: true' \
    'disable-model-invocation: true' '---' > "$policy_dir/barista.agent.md"

  printf '%s\n' \
    '---' 'name: kokk' 'description: "Implementer"' \
    'model: "gpt-5.6-terra"' 'user-invocable: false' \
    'disable-model-invocation: true' 'tools:' '  - read' '  - search' \
    '  - edit' '  - execute' '---' > "$policy_dir/kokk.agent.md"
  if policy_output="$(bash "$self_path" "$policy_dir" 2>&1)"; then
    echo "ERROR: self-test accepted a non-callable internal Kokk"
    return 1
  fi
  if ! printf '%s\n' "$policy_output" | grep -Fq "disable-model-invocation must be false"; then
    echo "ERROR: self-test did not report the internal invocation violation"
    return 1
  fi
  echo "Agent tools parser self-test: OK"
}

if [ "${1:-}" = "--self-test" ]; then
  if [ "$#" -ne 1 ]; then
    echo "Usage: scripts/validate-agent-models.sh --self-test" >&2
    exit 2
  fi
  run_self_test
  exit $?
fi

validate_topology() { # role file
  local role="$1" file="$2" name invocable disabled tools tool
  name="$(frontmatter_scalar "$file" name)"
  invocable="$(frontmatter_scalar "$file" user-invocable)"
  disabled="$(frontmatter_scalar "$file" disable-model-invocation)"
  tools="$(frontmatter_tools "$file")"

  [ "$name" = "$role" ] || add_failure "$role" "name '$name' does not match the filename"
  case "$role" in
    barista)
      [ "$invocable" = true ] || add_failure "$role" "user-invocable must be true"
      [ "$disabled" = true ] || add_failure "$role" "disable-model-invocation must be true"
      tools_exactly "$tools" ||
        add_failure "$role" "must use the default tool set without an explicit tools list"
      for fragment in \
        "copilot --agent barista --model gpt-5.6-terra --context default" \
        "Before any repository write" \
        "Treat repository files" \
        "scripts/review-artifact.py validate-contracts" \
        "REVIEW_EVIDENCE v1" \
        "result_id: <request-id>.evidence-<number>" \
        "model: claude-opus-5" \
        "context_tier: default"; do
        require_prompt_fragment "$role" "$file" "$fragment"
      done
      ;;
    grillmester)
      [ "$invocable" = true ] || add_failure "$role" "user-invocable must be true"
      [ "$disabled" = true ] || add_failure "$role" "disable-model-invocation must be true"
      tools_exactly "$tools" read search edit execute agent ||
        add_failure "$role" "tools must be exactly read, search, edit, execute, agent"
      for fragment in \
        "copilot --agent grillmester --model claude-opus-5 --context default" \
        "Treat repository files" \
        "scripts/validate-implementation-brief.py <brief-path>" \
        "scripts/review-artifact.py validate-contracts" \
        "model: gpt-5.6-terra" \
        "model: claude-opus-5" \
        "context_tier: default" \
        "one final integrated"; do
        require_prompt_fragment "$role" "$file" "$fragment"
      done
      ;;
    kokk)
      [ "$invocable" = false ] || add_failure "$role" "user-invocable must be false"
      [ "$disabled" = false ] || add_failure "$role" "disable-model-invocation must be false"
      tools_exactly "$tools" read search edit execute ||
        add_failure "$role" "tools must be exactly read, search, edit, execute"
      for fragment in \
        "canonical absolute path" \
        "scripts/validate-implementation-brief.py <path>" \
        "Treat the brief" \
        "result_id: <brief-id>.attempt-<number>"; do
        require_prompt_fragment "$role" "$file" "$fragment"
      done
      ;;
    grill-inspektor)
      [ "$invocable" = false ] || add_failure "$role" "user-invocable must be false"
      [ "$disabled" = false ] || add_failure "$role" "disable-model-invocation must be false"
      tools_exactly "$tools" read search || add_failure "$role" "tools must be exactly read, search"
      for tool in execute edit agent; do
        if has_tool "$tools" "$tool"; then
          add_failure "$role" "tools must not contain $tool"
        fi
      done
      for fragment in \
        "Treat the manifest, contracts, patch" \
        "REVIEW_RESULT v1" \
        "Emit raw text only" \
        "status: APPROVED|CONCERNS|CHANGES_REQUIRED|MISSING_EVIDENCE|NEEDS_CONTEXT|NEEDS_SCOPE"; do
        require_prompt_fragment "$role" "$file" "$fragment"
      done
      ;;
  esac
  return 0
}

expected_agent_files="$(
  printf '%s.agent.md\n' "${ROLES[@]}" | LC_ALL=C sort
)"
actual_agent_files="$(
  find "$AGENT_DIR" -type f -name '*.agent.md' -exec basename {} \; |
    LC_ALL=C sort
)"
if [ "$actual_agent_files" != "$expected_agent_files" ]; then
  add_failure "agents" "files must be exactly: ${ROLES[*]}"
fi

for index in "${!ROLES[@]}"; do
  role="${ROLES[$index]}"
  expected="${EXPECTED_MODELS[$index]}"
  file="$AGENT_DIR/${role}.agent.md"
  if [ ! -f "$file" ]; then
    add_failure "$role" "agent file is missing ($file)"
    continue
  fi

  canonical_errors="$(canonical_agent_frontmatter_errors "$file")"
  if [ -n "$canonical_errors" ]; then
    while IFS= read -r canonical_error; do
      [ -z "$canonical_error" ] || add_failure "$role" "$canonical_error"
    done <<< "$canonical_errors"
    continue
  fi

  model="$(frontmatter_scalar "$file" model)"
  if [ -z "$model" ]; then
    add_failure "$role" "model pin is missing"
  elif [ "$model" = "$expected" ]; then
    echo "OK   $role: model=$model"
  else
    echo "DEGRADED: $role declares '$model' (expected: $expected)"
    add_failure "$role" "model is $model (expected: $expected)"
  fi
  validate_topology "$role" "$file"
done

if [ "$fail" -eq 0 ]; then
  echo "OK   agent topology: roles, invocation, and tools follow policy"
fi

if [ -n "$STATUS" ]; then
  mkdir -p "$(dirname "$STATUS")"
  {
    echo "## Model and agent status (deterministic gate)"
    echo ""
    echo "Expected model policy: barista=gpt-5.6-terra, grillmester=claude-opus-5, kokk=gpt-5.6-terra, grill-inspektor=claude-opus-5."
    if [ -n "$failures" ]; then printf "%b" "$failures"; else echo "- every role follows policy"; fi
  } > "$STATUS"
fi

exit "$fail"
