#!/usr/bin/env bash
# Fails if the contract version already exists in GitHub Packages: published versions are
# immutable and must never be re-published. Expects HTTP 404 for a new version.
#
# Environment:
#   CONTRACT_VERSION   the version about to be published (X.Y.Z)
#   GITHUB_TOKEN       token with read access to the repository's packages
set -euo pipefail

version="$CONTRACT_VERSION"
package_url="https://maven.pkg.github.com/navikt/syfo-budstikka/no/nav/syfo/budstikka-kontrakt/${version}/budstikka-kontrakt-${version}.pom"
curl_config="$(mktemp)"
chmod 600 "$curl_config"
trap 'rm -f "$curl_config"' EXIT
printf 'user = "x-access-token:%s"\n' "$GITHUB_TOKEN" | dd of="$curl_config" status=none
unset GITHUB_TOKEN

status="$(
  curl \
    --silent \
    --show-error \
    --location \
    --connect-timeout 10 \
    --max-time 30 \
    --output /dev/null \
    --write-out "%{http_code}" \
    --config "$curl_config" \
    "$package_url"
)"
case "$status" in
  404)
    ;;
  200)
    echo "::error::Immutable contract version ${version} already exists in GitHub Packages."
    exit 1
    ;;
  *)
    echo "::error::Could not preflight ${package_url}: HTTP ${status}."
    exit 1
    ;;
esac
