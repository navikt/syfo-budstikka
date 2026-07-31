#!/usr/bin/env bash
# Deterministisk deklarasjonsgate: validerer at hver agents model:-pin er paa
# repoets allowlist og feiler hardt ved manglende eller ukjent pin. Gaten
# observerer ikke runtime-modell, tilgang, fallback eller faktisk modellvalg.
#
# Bruk:  scripts/validate-agent-models.sh [agents-dir]
# Exit:  0 = alle deklarasjoner tillatt, 1 = manglende/ukjent deklarasjon
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"

# Rolle -> tillatte deklarasjoner.
declare -A ALLOW=(
  [grillmester]="claude-opus-4.8"
  [grill-inspektor]="gpt-5.5"
)

fail=0
for role in "${!ALLOW[@]}"; do
  f="$AGENT_DIR/${role}.agent.md"
  if [ ! -f "$f" ]; then
    echo "MANGLER: $f"; fail=1; continue
  fi
  model="$(grep -E '^model:' "$f" | head -1 | sed -E 's/^model:[[:space:]]*"?([^"#]*)"?.*/\1/' | xargs)"
  if [ -z "$model" ]; then
    echo "FEIL: $role har ingen model:-pin"; fail=1; continue
  fi
  if [[ " ${ALLOW[$role]} " == *" $model "* ]]; then
    echo "OK   $role: $model"
  else
    echo "UGYLDIG PIN: $role deklarerer '$model' (tillatt: ${ALLOW[$role]})"
    fail=1
  fi
done

exit $fail
