#!/usr/bin/env bash
# Creates the GitHub Release for a contract version from the checked-in release notes, or verifies
# that an already-existing release matches them exactly (idempotent rerun).
#
# Environment:
#   GH_TOKEN           token with contents: write (release creation)
#   CONTRACT_VERSION   the published version (X.Y.Z)
set -euo pipefail

tag="kontrakt/v${CONTRACT_VERSION}"
title="budstikka-kontrakt ${CONTRACT_VERSION}"
notes="docs/releases/kontrakt/${CONTRACT_VERSION}.md"

# A failed lookup (absent release or transient error) falls through to create, which can never
# overwrite an existing release: a duplicate create fails and a rerun converges.
if release="$(gh release view "$tag" --json name,body,isDraft,isPrerelease)"; then
  RELEASE="$release" EXPECTED_TITLE="$title" NOTES="$notes" python3 -c '
import json
import os
from pathlib import Path

try:
    release = json.loads(os.environ["RELEASE"])
except json.JSONDecodeError as error:
    raise SystemExit(f"Could not parse existing GitHub Release: {error}")

expected = {
    "name": os.environ["EXPECTED_TITLE"],
    "body": Path(os.environ["NOTES"]).read_text(encoding="utf-8").rstrip("\n"),
    "isDraft": False,
    "isPrerelease": False,
}
actual = {
    "name": release.get("name"),
    "body": release.get("body", "").rstrip("\n"),
    "isDraft": release.get("isDraft"),
    "isPrerelease": release.get("isPrerelease"),
}
if actual != expected:
    raise SystemExit(
        "Existing GitHub Release does not match checked-in expectations: "
        f"expected {expected!r}, got {actual!r}."
    )
'
  echo "Existing GitHub Release matches checked-in expectations."
else
  gh release create "$tag" \
    --verify-tag \
    --title "$title" \
    --notes-file "$notes"
fi
