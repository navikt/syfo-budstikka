#!/usr/bin/env bash
# Decides whether the contract compatibility gates must run in CI, by checking the changed files
# against the contract-relevant paths. Fails open (gates run) when no reliable diff is available.
#
# Environment:
#   BASE_SHA        base commit for the diff; may be empty (e.g. workflow_dispatch)
#   GITHUB_SHA      head commit (default GitHub Actions environment variable)
#   GITHUB_OUTPUT   step output file (default GitHub Actions environment variable)
#
# Outputs:
#   run_contract_gates   "true" or "false"
#   reason               human-readable explanation
set -euo pipefail

run_contract_gates=false
reason="no contract-relevant files changed"

if [[ -z "${BASE_SHA:-}" || -z "${GITHUB_SHA:-}" ]]; then
  run_contract_gates=true
  reason="no reliable base or head SHA is available"
elif ! git cat-file -e "${BASE_SHA}^{commit}" || ! git cat-file -e "${GITHUB_SHA}^{commit}"; then
  run_contract_gates=true
  reason="base or head SHA is unavailable in the checkout"
else
  # Captured in a variable so a git diff failure fails the script instead of silently yielding an
  # empty file list (set -e does not reach process substitutions).
  changed_files="$(git diff --name-only "$BASE_SHA" "$GITHUB_SHA")"
  while IFS= read -r path; do
    case "$path" in
      kontrakt/* | build-logic/* | \
      build.gradle.kts | settings.gradle.kts | gradle.properties | gradlew | gradlew.bat | \
      gradle/wrapper/* | gradle/libs.versions.toml | \
      scripts/verify-contract-baseline.sh | scripts/detect-contract-changes.sh | \
      scripts/require-contract-tag-ruleset.sh | scripts/preflight-contract-version.sh | \
      scripts/verify-staged-contract-subjects.sh | scripts/create-contract-release.sh | \
      .github/workflows/ci-reusable.yml | .github/workflows/publish-kontrakt.yml)
        run_contract_gates=true
        reason="contract-relevant file changed: $path"
        break
        ;;
    esac
  done <<< "$changed_files"
fi

printf 'run_contract_gates=%s\nreason=%s\n' "$run_contract_gates" "$reason" >> "$GITHUB_OUTPUT"
echo "Contract compatibility gates: $run_contract_gates ($reason)."
