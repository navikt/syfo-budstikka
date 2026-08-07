#!/usr/bin/env bash
set -euo pipefail

readonly stable_semver='(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)'
readonly mirror_repository="https://github-package-registry-mirror.gc.nav.no/cached/maven-release"

current_version=""
if [[ "${1:-}" == "--current-version" ]]; then
    current_version="${2:-}"
    if [[ $# -ne 2 ]] || [[ ! "$current_version" =~ ^$stable_semver$ ]]; then
        printf 'Usage: %s [--current-version X.Y.Z]\n' "$0" >&2
        exit 2
    fi
elif [[ $# -ne 0 ]]; then
    printf 'Usage: %s [--current-version X.Y.Z]\n' "$0" >&2
    exit 2
fi

release_tags="$(git tag --list 'kontrakt/v*')"

expected_version="$(
CURRENT_VERSION="$current_version" \
RELEASE_TAGS="$release_tags" \
STABLE_SEMVER="$stable_semver" \
python3 -c '
import os
import re
import sys

current_version = os.environ["CURRENT_VERSION"]
stable_semver = re.compile(r"^" + os.environ["STABLE_SEMVER"] + r"$")
tags = [tag for tag in os.environ["RELEASE_TAGS"].splitlines() if tag]

versions = []
for tag in tags:
    version = tag.removeprefix("kontrakt/v")
    if not stable_semver.fullmatch(version):
        raise SystemExit(
            f"Malformed contract release tag {tag!r}; expected kontrakt/vX.Y.Z without leading zeroes."
        )
    versions.append(version)

def version_key(version):
    return tuple(map(int, version.split(".")))

if not current_version:
    expected = max(versions, key=version_key) if versions else "first-release"
else:
    current_tag = f"kontrakt/v{current_version}"
    if current_tag not in tags:
        raise SystemExit(f"Current contract release tag {current_tag!r} is absent.")

    prior_versions = [version for version in versions if version != current_version]
    if current_version == "0.1.0":
        if prior_versions:
            raise SystemExit("Locked first release 0.1.0 must have no prior contract release tag.")
        expected = "first-release"
    else:
        if not prior_versions:
            raise SystemExit(
                f"Release {current_version} has no prior contract release tag; refusing to infer a baseline."
            )
        expected = max(prior_versions, key=version_key)
        if version_key(current_version) <= version_key(expected):
            raise SystemExit(
                f"Release version {current_version} must be greater than authoritative prior "
                f"release version {expected}."
            )

print(expected)
'
)"

if [[ "$expected_version" == "first-release" ]]; then
    printf '%s\n' "first-release"
    exit 0
fi

pom_url="${mirror_repository}/no/nav/syfo/budstikka-kontrakt/${expected_version}/budstikka-kontrakt-${expected_version}.pom"
if ! status="$(
    curl \
        --connect-timeout 10 \
        --max-time 30 \
        --silent \
        --show-error \
        --location \
        --output /dev/null \
        --write-out "%{http_code}" \
        "$pom_url"
)"; then
    printf 'Could not resolve contract baseline POM through the Nav mirror; wait for the mirror and retry: %s\n' \
        "$pom_url" >&2
    exit 1
fi

if [[ "$status" != "200" ]]; then
    printf 'Contract baseline POM is unavailable through the Nav mirror (HTTP %s); wait for the mirror and retry: %s\n' \
        "$status" "$pom_url" >&2
    exit 1
fi

printf '%s\n' "$expected_version"
