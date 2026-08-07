#!/usr/bin/env bash
# Fails unless an active GitHub tag ruleset matches refs/tags/kontrakt/v* exactly (no excludes)
# and restricts creation, update and deletion. Run before any contract release work.
#
# Environment:
#   GH_TOKEN            token for gh api (the workflow's installation token)
#   GITHUB_REPOSITORY   owner/repo (default GitHub Actions environment variable)
set -euo pipefail

# Authenticated lookup: gh api fails the script (closed) on any HTTP, network or parse error, and
# the installation token avoids the shared-runner unauthenticated rate limit.
tag_ruleset_ids="$(
  gh api --paginate "repos/${GITHUB_REPOSITORY}/rulesets?includes_parents=true&per_page=100" \
    --jq '.[] | select(.target == "tag" and .enforcement == "active") | .id'
)"

while IFS= read -r ruleset_id; do
  [[ -n "$ruleset_id" ]] || continue
  if [[ ! "$ruleset_id" =~ ^[0-9]+$ ]]; then
    echo "::error::GitHub ruleset list returned an invalid ruleset ID."
    exit 1
  fi
  ruleset_match="$(
    gh api "repos/${GITHUB_REPOSITORY}/rulesets/${ruleset_id}?includes_parents=true" \
      --jq '
        if .target == "tag"
          and .enforcement == "active"
          and .conditions.ref_name.include == ["refs/tags/kontrakt/v*"]
          and (.conditions.ref_name.exclude // []) == []
          and (["creation", "update", "deletion"] - [.rules[]?.type]) == []
        then "match"
        else "no-match"
        end
      '
  )"
  if [[ "$ruleset_match" == "match" ]]; then
    echo "Active tag ruleset restricting creation, update and deletion matches refs/tags/kontrakt/v*."
    exit 0
  fi
done <<< "$tag_ruleset_ids"

echo "::error::Maintainers must configure an active tag ruleset matching refs/tags/kontrakt/v* with creation, update and deletion restrictions before the first contract release."
exit 1
