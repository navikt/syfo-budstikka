#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d "$TMPDIR/scan-grill-test.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT
git -C "$test_dir" init --quiet
cd "$test_dir"

credential_field=password
claim_field=claim_token
other_field=secret
token_field=token

check() {
  local expected="$1" content="$2" result=0
  printf '%s\n' "$content" > fixture.txt
  git add fixture.txt
  bash "$root/scripts/scan-grill-pii.sh" > result.txt 2>&1 || result=$?
  if [ "$result" -ne "$expected" ]; then
    printf 'Scanner regression failed: expected exit %s, got %s\n' "$expected" "$result"
    cat result.txt
    exit 1
  fi
}

check 0 "    val $token_field: ClaimToken,"
check 0 "    val $credential_field: String"
check 0 "    $credential_field = postgres.$credential_field,"
check 0 "UPDATE delivery SET $claim_field = gen_random_uuid(), state = 'CLAIMED'"
check 0 "UPDATE delivery SET $claim_field = NULL, state = 'SENT'"
check 1 "$credential_field = \"synthetic-test-value\""
check 1 "$token_field: ClaimToken = \"synthetic-test-value\""
check 1 "UPDATE delivery SET $claim_field = 'synthetic-test-value'"
check 1 "    $credential_field = postgres.$credential_field, $other_field = \"synthetic-test-value\""
check 1 "UPDATE delivery SET $claim_field = NULL, $other_field = 'synthetic-test-value'"
check 1 "    val $token_field: ClaimToken, $other_field = \"synthetic-test-value\""
check 1 "    val $credential_field: String = \"synthetic-test-value\""
check 1 "UPDATE delivery SET $claim_field = gen_random_uuid(), $other_field = 'synthetic-test-value'"
check 1 "    $credential_field = postgres.$credential_field, $token_field = \"synthetic-test-value\""
check 1 "$(printf '%011d' 0) $(printf '%011d' 123)"
check 1 "$(printf '%011d' 123)"
check 0 "$(printf '%011d' 0)"
check 1 "Bearer $(printf 'synthetic%.0s' {1..3})"
check 1 "$(printf 'ey%s' 'Jsynthetic-test-token')"
check 1 "$(printf '%s' '-----' 'BEGIN SYNTHETIC TEST KEY-----')"
printf 'Scanner regression tests passed (20 cases).\n'
