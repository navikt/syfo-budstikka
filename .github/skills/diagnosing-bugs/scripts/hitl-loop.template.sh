#!/usr/bin/env bash
# Human-in-the-loop reproduction loop (last resort in phase 1).
# Copy this file, edit the steps below, and run it.
# The agent runs the script; the user follows prompts in their terminal.
#
# Usage:
#   bash hitl-loop.template.sh
#
# Two helpers:
#   step "<instruction>"        -> show instruction, wait for Enter
#   capture VAR "<question>"    -> show question, read answer into VAR
#
# The captured values are printed as KEY=VALUE for the agent to parse.

set -euo pipefail

step() {
  printf '\n>>> %s\n' "$1"
  read -r -p "    [Press Enter when done] " _
}

capture() {
  local var="$1" question="$2" answer
  printf '\n>>> %s\n' "$question"
  read -r -p "    > " answer
  printf -v "$var" '%s' "$answer"
}

# --- edit below ---------------------------------------------------------

step "Start the app locally with ./gradlew run (or port-forward to dev-gcp)."

capture STATUS "Run curl -s -o /dev/null -w '%{http_code}' against the failing route. Which HTTP status?"

capture ERROR "Paste the error message from the application log (or 'none'):"

# --- edit above ---------------------------------------------------------

printf '\n--- Captured ---\n'
printf 'STATUS=%s\n' "$STATUS"
printf 'ERROR=%s\n' "$ERROR"
