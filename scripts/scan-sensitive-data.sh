#!/usr/bin/env bash
# Deterministisk PII/hemmelighet-gate for innholdet som faktisk stages.
#
# Bruk:  scripts/scan-sensitive-data.sh
# Kjøres av .githooks/pre-commit (aktiver med: git config core.hooksPath .githooks).
# Skriver ALDRI selve treffet (det KAN være PII) — kun fil + linjenr + hvilken regel.
# Exit:  0 = rent, 1 = mistanke (blokkerer commit)
set -uo pipefail

[ "$#" -eq 0 ] || {
  echo "Bruk: scripts/scan-sensitive-data.sh" >&2
  exit 2
}

hits=0
scan_errors=0

# Regel-navn -> ERE-mønster. Portabelt mellom GNU- og BSD-grep (ingen \b).
rule_names=(fnr-dnr jwt pem hemmelighet bearer)
rule_regex=(
  '(^|[^0-9])[0-9]{11}([^0-9]|$)'
  'eyJ[A-Za-z0-9_-]{10,}'
  '-{5}BEGIN'
  '(passord|password|secret|client[_-]?secret|api[_-]?key|token)[[:space:]]*[:=]'
  'Bearer[[:space:]]+[A-Za-z0-9._-]{10,}'
)

# scan_file <visningsetikett> <fil-på-disk> [post-image-linjenummer]
scan_file() {
  local label="$1" file="$2" line_map="${3:-}" i name pat lines normalized
  local record_line post_image_line
  local -a record_lines post_image_lines
  # Kanonisk syntetisk personident i eksempler er BARE NULLER (jf. «tydelig syntetiske verdier»
  # i docs/sende-varsler.md og README-quickstarten). Bare-nuller er ugyldig som fnr/d-nr og
  # utvetydig ikke-PII, så den nulles ut før skanning; alle andre 11-sifrede sekvenser flagges
  # fortsatt — også om de står på samme linje som den syntetiske verdien.
  if ! normalized="$(mktemp)"; then
    echo "FEIL: kunne ikke klargjøre skanning av $label." >&2
    scan_errors=1
    return
  fi
  if ! sed 's/00000000000/SYNTETISK-PERSONIDENT/g' "$file" > "$normalized" 2>/dev/null &&
    ! cp "$file" "$normalized"; then
    echo "FEIL: kunne ikke lese staget innhold for $label." >&2
    rm -f "$normalized"
    scan_errors=1
    return
  fi
  for i in "${!rule_names[@]}"; do
    name="${rule_names[$i]}"; pat="${rule_regex[$i]}"
    # -I hopper binærfiler, -n gir linjenr; vi beholder KUN linjenrene (kaster innholdet).
    lines="$(grep -InE -e "$pat" "$normalized" 2>/dev/null | cut -d: -f1 | paste -sd, -)" || true
    if [ -n "$lines" ]; then
      if [ -n "$line_map" ]; then
        post_image_lines=()
        IFS=, read -r -a record_lines <<< "$lines"
        for record_line in "${record_lines[@]}"; do
          post_image_line="$(sed -n "${record_line}p" "$line_map")"
          if [ -z "$post_image_line" ]; then
            echo "FEIL: kunne ikke finne post-image-linje for $label." >&2
            rm -f "$normalized"
            scan_errors=1
            return
          fi
          post_image_lines+=("$post_image_line")
        done
        lines="$(IFS=,; echo "${post_image_lines[*]}")"
      fi
      echo "PII-mistanke ($name) i $label — linje(r): $lines"
      hits=1
    fi
  done
  rm -f "$normalized"
}

# scan_added_lines <visningsetikett> <sti>
# Skann bare linjer som er lagt til i indeksens post-image. Ingen kontekstlinjer tas med.
scan_added_lines() {
  local label="$1" path="$2" content_file line_map

  if ! content_file="$(mktemp)" || ! line_map="$(mktemp)"; then
    echo "FEIL: kunne ikke klargjøre skanning av $label." >&2
    rm -f "${content_file:-}" "${line_map:-}"
    scan_errors=1
    return
  fi

  if ! git diff --cached --no-renames --no-ext-diff --no-color --no-textconv --unified=0 -- "$path" |
    awk -v content_file="$content_file" -v line_map="$line_map" '
      /^@@ / {
        split($0, fields, " ")
        post_image_line = fields[3]
        sub(/^\+/, "", post_image_line)
        sub(/,.*/, "", post_image_line)
        in_hunk = 1
        next
      }
      in_hunk && /^\+/ {
        print substr($0, 2) > content_file
        print post_image_line > line_map
        post_image_line++
      }
    '; then
    echo "FEIL: kunne ikke lese indekstillegg for $label." >&2
    rm -f "$content_file" "$line_map"
    scan_errors=1
    return
  fi

  scan_file "$label" "$content_file" "$line_map"
  rm -f "$content_file" "$line_map"
}

# Skann det som er staget for commit (uansett mappe) — innholdet slik det committes.
if git rev-parse --git-dir >/dev/null 2>&1; then
  # --no-renames representerer kopier som nye stier, slik at hele nye filen skannes.
  changes="$(mktemp)"
  if ! git diff --cached --no-renames --name-status -z --diff-filter=AM > "$changes"; then
    echo "FEIL: kunne ikke lese Git-indeksen." >&2
    rm -f "$changes"
    scan_errors=1
  else
    while IFS= read -r -d '' status && IFS= read -r -d '' path; do
      printf -v safe_path '%q' "$path"
      label="staget: $safe_path"
      case "$status" in
        A)
          if ! staged_file="$(mktemp)"; then
            echo "FEIL: kunne ikke klargjøre skanning av $label." >&2
            scan_errors=1
            continue
          fi
          if git show ":$path" > "$staged_file" 2>/dev/null; then
            scan_file "$label" "$staged_file"
          else
            echo "FEIL: kunne ikke lese staget innhold for $label." >&2
            scan_errors=1
          fi
          rm -f "$staged_file"
          ;;
        M)
          scan_added_lines "$label" "$path"
          ;;
      esac
    done < "$changes"
    rm -f "$changes"
  fi
fi

if [ "$hits" -ne 0 ] || [ "$scan_errors" -ne 0 ]; then
  echo ""
  if [ "$hits" -ne 0 ]; then
    echo "BLOKKERT: mulig PII/hemmelighet funnet. Fjern eller masker før commit."
    echo "  Falsk positiv? Verifiser manuelt; gaten er bevisst streng (defense-in-depth)."
  fi
  if [ "$scan_errors" -ne 0 ]; then
    echo "BLOKKERT: kunne ikke fullføre skanningen av staget innhold."
  fi
  exit 1
fi
exit 0
