#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly source_root="app/src"
readonly native_root="native/core"
failures=0

reject() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number "$pattern" "$@")"; then
    echo "::error title=Universality policy violation::$label"
    echo "Universality violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "::error title=Universality scanner failure::$label (rg exit $status)"
      echo "Universality guard failed to scan: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

reject 'manufacturer/model dispatch' \
  '\bBuild\s*\.\s*(?:MANUFACTURER|MODEL|BRAND|DEVICE|HARDWARE|SOC_MANUFACTURER|SOC_MODEL)\b|import\s+android\.os\.Build\.(?:MANUFACTURER|MODEL|BRAND|DEVICE|HARDWARE|SOC_MANUFACTURER|SOC_MODEL)\b' \
  --glob '*.kt' --glob '*.java' "$source_root"
reject 'SoC or sensor-vendor dispatch' \
  '(?i)\b(?:soc|chipset|sensorVendor)\b\s*(?:==|!=|\.equals|\.contains)' \
  --glob '*.kt' --glob '*.java' "$source_root"
reject 'numeric camera ID comparison' \
  '(?i)(?:\b(?:camera|transport|open|physical|logical)[A-Za-z0-9_]*Id\b\s*(?:==|===|!=|!==)\s*[\x22\x27][0-9]+[\x22\x27]|[\x22\x27][0-9]+[\x22\x27]\s*(?:==|===|!=|!==)\s*\b(?:camera|transport|open|physical|logical)[A-Za-z0-9_]*Id\b|[\x22\x27][0-9]+[\x22\x27]\s*->)' \
  --glob '*.kt' --glob '*.java' "$source_root"
reject 'literal numeric native camera open' \
  'openCamera\s*\(\s*[\x22\x27][0-9]+[\x22\x27]' \
  --glob '*.c' --glob '*.cc' --glob '*.cpp' --glob '*.cxx' \
  --glob '*.h' --glob '*.hpp' --glob '*.hxx' "$native_root"

if ((failures > 0)); then
  exit 1
fi
echo 'Universality verification passed.'
