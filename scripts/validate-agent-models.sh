#!/usr/bin/env bash
# Deterministisk gate (Grillmester prinsipp 4): validerer at hver agents
# model:-pin er paa allowlist. Feiler hardt. Degradering oppdages HER, aldri
# av modellen selv. Skriver status til .grill/MODELL-STATUS.md, som @grillmester leser
# og gjengir som transparens-kontrakt.
#
# Bruk:  scripts/validate-agent-models.sh [agents-dir]
# Exit:  0 = alle paa gulv, 1 = degradert/mangler (hardt fail i CI/oppstart)
set -euo pipefail

AGENT_DIR="${1:-.github/agents}"
STATUS="${GRILL_STATUS:-.grill/MODELL-STATUS.md}"

# Rolle -> gyldige modeller. Sterk-only: ingen svake tier.
declare -A ALLOW=(
  [grillmester]="claude-opus-4.8"
  [grill-inspektor]="gpt-5.5"
)

fail=0
degraded=""
for role in "${!ALLOW[@]}"; do
  f="$AGENT_DIR/${role}.agent.md"
  if [ ! -f "$f" ]; then
    echo "MANGLER: $f"; degraded+="- $role: agentfil mangler\n"; fail=1; continue
  fi
  model="$(grep -E '^model:' "$f" | head -1 | sed -E 's/^model:[[:space:]]*"?([^"#]*)"?.*/\1/' | xargs)"
  if [ -z "$model" ]; then
    echo "FEIL: $role har ingen model:-pin"; degraded+="- $role: model ikke satt\n"; fail=1; continue
  fi
  if [[ " ${ALLOW[$role]} " == *" $model "* ]]; then
    echo "OK   $role: $model"
  else
    echo "DEGRADERT: $role kjorer paa '$model' (forventet: ${ALLOW[$role]})"
    degraded+="- $role kjorer paa $model — forvent lavere kvalitet\n"; fail=1
  fi
done

mkdir -p "$(dirname "$STATUS")"
{
  echo "## Modell-status (deterministisk gate)"
  if [ -n "$degraded" ]; then printf "%b" "$degraded"; else echo "- alle roller paa gulv"; fi
} > "$STATUS"

exit $fail
