#!/usr/bin/env bash
# Verifies byte-for-byte that the staged Maven repository files (the attestation subjects) are the
# exact files Gradle built for publication. Run both before attestation and after publication, so a
# rebuild between the two steps can never change what was attested or uploaded unnoticed.
#
# Environment:
#   CONTRACT_VERSION   the version being published (X.Y.Z)
set -euo pipefail

version="$CONTRACT_VERSION"
staging_directory="kontrakt/build/staging-maven-repository/no/nav/syfo/budstikka-kontrakt/${version}"

compare() {
  local publication_input="$1"
  local staged_subject="$2"

  test -f "$publication_input"
  test -f "$staged_subject"
  cmp --silent "$publication_input" "$staged_subject" ||
    {
      echo "::error::Staged attestation subject differs from publication input: ${staged_subject}."
      exit 1
    }
}

compare "kontrakt/build/libs/kontrakt-${version}.jar" \
  "${staging_directory}/budstikka-kontrakt-${version}.jar"
compare "kontrakt/build/libs/kontrakt-${version}-sources.jar" \
  "${staging_directory}/budstikka-kontrakt-${version}-sources.jar"
compare "kontrakt/build/publications/contract/pom-default.xml" \
  "${staging_directory}/budstikka-kontrakt-${version}.pom"
compare "kontrakt/build/publications/contract/module.json" \
  "${staging_directory}/budstikka-kontrakt-${version}.module"
