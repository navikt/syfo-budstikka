#!/usr/bin/env bash
# Small defense-in-depth gate for likely PII or secrets in staged files. The
# legacy .grill directory is also scanned when it exists.
#
# Usage: scripts/scan-grill-pii.sh [directory] (default: .grill)
# The scanner reports only file, line number, and rule name—never matched text.
set -uo pipefail

scan_dir="${1:-.grill}"
hits=0

rule_names=(fnr-dnr jwt pem credential bearer)
rule_regex=(
  '(^|[^[:alnum:]])[0-9]{11}([^[:alnum:]]|$)'
  'eyJ[A-Za-z0-9_-]{10,}'
  '-{5}BEGIN'
  '(passord|password|secret|client[_-]?secret|api[_-]?key|token)[[:space:]]*[:=]'
  'Bearer[[:space:]]+[A-Za-z0-9._-]{10,}'
)

scan_file() {
  local label="$1" file="$2" i name pattern lines
  for i in "${!rule_names[@]}"; do
    name="${rule_names[$i]}"
    pattern="${rule_regex[$i]}"
    lines="$(grep -InE -e "$pattern" "$file" 2>/dev/null | cut -d: -f1 | paste -sd, -)" || true
    if [ -n "$lines" ]; then
      echo "Possible sensitive data ($name) in $label — line(s): $lines"
      hits=1
    fi
  done
}

if [ -d "$scan_dir" ]; then
  while IFS= read -r file; do
    [ -n "$file" ] && scan_file "$file" "$file"
  done < <(find "$scan_dir" -type f \( -name '*.md' -o -name '*.txt' \) 2>/dev/null)
fi

if git rev-parse --git-dir >/dev/null 2>&1; then
  while IFS= read -r file; do
    [ -z "$file" ] && continue
    staged="$(mktemp)"
    if git show ":$file" > "$staged" 2>/dev/null; then
      scan_file "staged: $file" "$staged"
    fi
    rm -f "$staged"
  done < <(git diff --cached --name-only --diff-filter=ACM 2>/dev/null)
fi

if [ "$hits" -ne 0 ]; then
  echo "BLOCKED: possible PII or secret. Remove or mask it before commit."
  exit 1
fi
