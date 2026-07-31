#!/usr/bin/env bash

set -euo pipefail

bootstrap_directory=
scratch_directory=

cleanup() {
  if [[ -n "$bootstrap_directory" ]]; then
    rm -rf -- "$bootstrap_directory"
  fi
  if [[ -n "$scratch_directory" ]]; then
    rm -rf -- "$scratch_directory"
  fi
}
trap cleanup EXIT

fail() {
  printf '%s\n' "$1" >&2
  exit 2
}

canonical_directory() {
  cd "$1" 2>/dev/null && pwd -P
}

configured_tmp_root=${TMPDIR:-/tmp}
for temporary_candidate in "$configured_tmp_root" /tmp /var/tmp; do
  temporary_candidate=$(canonical_directory "$temporary_candidate") || continue
  if bootstrap_directory=$(mktemp -d "$temporary_candidate/handoff-bootstrap.XXXXXX" 2>/dev/null); then
    configured_tmp_root=$temporary_candidate
    break
  fi
done
[[ -n "$bootstrap_directory" ]] || fail 'Cannot create private bootstrap space in OS temporary storage.'
export TMPDIR="$bootstrap_directory"

repository_root=$(git rev-parse --show-toplevel 2>/dev/null) || fail 'Cannot resolve the Git worktree root.'
git rev-parse --verify 'HEAD^{commit}' >/dev/null 2>&1 || fail 'Cannot fingerprint a worktree without an existing HEAD commit.'
cd "$repository_root"
repository_root=$(pwd -P) || fail 'Cannot canonicalize the Git worktree root.'
[[ "$repository_root" != / ]] || fail 'Cannot create fingerprint scratch space outside a root-level Git worktree.'

for temporary_candidate in "$configured_tmp_root" /tmp /var/tmp; do
  temporary_candidate=$(canonical_directory "$temporary_candidate") || continue
  if [[ "$temporary_candidate" == "$repository_root" || "$temporary_candidate" == "$repository_root/"* ]]; then
    continue
  fi
  if scratch_directory=$(mktemp -d "$temporary_candidate/handoff-fingerprint.XXXXXX" 2>/dev/null); then
    break
  fi
done
[[ -n "$scratch_directory" ]] || fail 'Cannot create fingerprint scratch space outside the Git worktree.'
rm -rf -- "$bootstrap_directory"
bootstrap_directory=
export TMPDIR="$scratch_directory"

index_entries="$scratch_directory/index-entries"
index_flags="$scratch_directory/index-flags"
unmerged_entries="$scratch_directory/unmerged-entries"
tracked_paths="$scratch_directory/tracked-paths"
untracked_paths="$scratch_directory/untracked-paths"
special_paths="$scratch_directory/special-paths"
status_state="$scratch_directory/status-state"
manifest="$scratch_directory/manifest"

git ls-files --stage -z >"$index_entries" || fail 'Cannot read the Git index.'
git ls-files -v -z >"$index_flags" || fail 'Cannot read Git index flags.'
git ls-files --unmerged -z >"$unmerged_entries" || fail 'Cannot inspect the index for conflicts.'
git ls-files --cached -z >"$tracked_paths" || fail 'Cannot enumerate tracked paths.'
git ls-files --others --exclude-standard -z >"$untracked_paths" || fail 'Cannot enumerate non-ignored untracked paths.'
find . -path './.git' -prune -o ! -type d ! -type f ! -type l -print0 >"$special_paths" || fail 'Cannot inspect the worktree for special filesystem entries.'
git status --porcelain=v2 -z --branch --untracked-files=all >"$status_state" || fail 'Cannot record the complete Git status.'

if [[ -s "$unmerged_entries" ]]; then
  fail 'Cannot fingerprint an unmerged index exactly; resolve the merge before handoff.'
fi

while IFS= read -r -d '' index_entry; do
  if [[ "$index_entry" == 160000\ * ]]; then
    gitlink_path=${index_entry#*$'\t'}
    fail "Cannot fingerprint tracked submodule exactly: $gitlink_path"
  fi
done <"$index_entries"

while IFS= read -r -d '' special_path; do
  relative_path=${special_path#./}
  if git check-ignore -q -- "$relative_path"; then
    continue
  else
    ignore_status=$?
    if [[ "$ignore_status" -gt 1 ]]; then
      fail "Cannot evaluate ignore rules for special filesystem entry: $relative_path"
    fi
  fi
  fail "Cannot fingerprint non-ignored special filesystem entry: $relative_path"
done <"$special_paths"

printf 'worktree-fingerprint-v6\0head\0' >"$manifest" || fail 'Cannot initialize the fingerprint manifest.'
git rev-parse HEAD >>"$manifest" || fail 'Cannot record HEAD in the fingerprint manifest.'
printf '\0index\0' >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
cat "$index_entries" >>"$manifest" || fail 'Cannot record the Git index.'
printf '\0index-flags\0' >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
cat "$index_flags" >>"$manifest" || fail 'Cannot record Git index flags.'
printf '\0status\0' >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
cat "$status_state" >>"$manifest" || fail 'Cannot record the complete Git status.'

append_path() {
  local scope=$1
  local path=$2
  local kind content_hash

  if [[ -L "$path" ]]; then
    printf '\0%s:symlink:%s\0' "$scope" "$path" >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
    readlink "./$path" >>"$manifest" || fail "Cannot read symlink target: $path"
    printf '\0' >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
  elif [[ -f "$path" ]]; then
    if [[ -x "$path" ]]; then
      kind=executable
    else
      kind=regular
    fi
    content_hash=$(git hash-object --no-filters -- "$path") || fail "Cannot hash file contents: $path"
    printf '\0%s:%s:%s\0%s\0' "$scope" "$kind" "$path" "$content_hash" >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
  elif [[ ! -e "$path" ]]; then
    printf '\0%s:missing:%s\0' "$scope" "$path" >>"$manifest" || fail 'Cannot write the fingerprint manifest.'
  elif [[ -d "$path" && "$scope" == untracked ]]; then
    fail "Cannot fingerprint embedded Git repository exactly: $path"
  else
    fail "Cannot fingerprint unsupported worktree entry exactly: $path"
  fi
}

while IFS= read -r -d '' tracked_path; do
  append_path tracked "$tracked_path"
done <"$tracked_paths"

while IFS= read -r -d '' untracked_path; do
  append_path untracked "$untracked_path"
done <"$untracked_paths"

fingerprint=$(git hash-object --stdin <"$manifest") || fail 'Cannot hash the fingerprint manifest.'
printf '%s\n' "$fingerprint"
