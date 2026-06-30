#!/usr/bin/env bash
# Deterministisk PII/hemmelighet-gate (Grillmester: harde begrensninger er gater i
# kode, ikke forespørsler i prosa). `.grill/` behandles som om den var offentlig
# (jf. /handoff) — aldri fnr/d-nr, tokens, passord, klartekst-navn eller hemmeligheter.
# Denne gaten håndhever det på (1) `.grill/`-arbeidstreet og (2) det som faktisk stages.
#
# Bruk:  scripts/scan-grill-pii.sh [dir]      (default-dir: .grill)
# Kjøres av .githooks/pre-commit (aktiver med: git config core.hooksPath .githooks).
# Skriver ALDRI selve treffet (det KAN være PII) — kun fil + linjenr + hvilken regel.
# Exit:  0 = rent, 1 = mistanke (blokkerer commit)
set -uo pipefail

DIR="${1:-.grill}"
hits=0

# Regel-navn -> ERE-mønster. Portabelt mellom GNU- og BSD-grep (ingen \b).
rule_names=(fnr-dnr jwt pem hemmelighet bearer)
rule_regex=(
  '(^|[^0-9])[0-9]{11}([^0-9]|$)'
  'eyJ[A-Za-z0-9_-]{10,}'
  '-{5}BEGIN'
  '(passord|password|secret|client[_-]?secret|api[_-]?key|token)[[:space:]]*[:=]'
  'Bearer[[:space:]]+[A-Za-z0-9._-]{10,}'
)

# scan_file <visningsetikett> <fil-på-disk>
scan_file() {
  local label="$1" file="$2" i name pat lines
  for i in "${!rule_names[@]}"; do
    name="${rule_names[$i]}"; pat="${rule_regex[$i]}"
    # -I hopper binærfiler, -n gir linjenr; vi beholder KUN linjenrene (kaster innholdet).
    lines="$(grep -InE -e "$pat" "$file" 2>/dev/null | cut -d: -f1 | paste -sd, -)" || true
    if [ -n "$lines" ]; then
      echo "PII-mistanke ($name) i $label — linje(r): $lines"
      hits=1
    fi
  done
}

# 1) Arbeidstre-skann av .grill/ (gitignorert, men behandles som offentlig).
if [ -d "$DIR" ]; then
  while IFS= read -r f; do
    [ -n "$f" ] && scan_file "$f" "$f"
  done < <(find "$DIR" -type f \( -name '*.md' -o -name '*.txt' \) 2>/dev/null)
fi

# 2) Skann det som er staget for commit (uansett mappe) — innholdet slik det committes.
if git rev-parse --git-dir >/dev/null 2>&1; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    t="$(mktemp)"
    if git show ":$f" > "$t" 2>/dev/null; then
      scan_file "staget: $f" "$t"
    fi
    rm -f "$t"
  done < <(git diff --cached --name-only --diff-filter=ACM 2>/dev/null)
fi

if [ "$hits" -ne 0 ]; then
  echo ""
  echo "BLOKKERT: mulig PII/hemmelighet funnet. Fjern eller masker før commit."
  echo "  (.grill/ behandles som offentlig — ID-er/correlation er ok, personopplysninger og hemmeligheter ikke.)"
  echo "  Falsk positiv? Verifiser manuelt; gaten er bevisst streng (defense-in-depth)."
  exit 1
fi
exit 0
