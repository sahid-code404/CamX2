#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
failures=0
readonly camera_root="app/src/main/java/com/sahidcode404/camx/core/camera"
readonly preview_metrics="$camera_root/preview/PreviewFrameMetrics.kt"

reject() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number "$pattern" "$@")"; then
    echo "::error title=Hot-path policy violation::$label"
    echo "Hot-path violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "::error title=Hot-path scanner failure::$label (rg exit $status)"
      echo "Hot-path guard failed to scan: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

reject_text() {
  local label="$1"
  local pattern="$2"
  local text="$3"
  local matches
  if matches="$(printf '%s\n' "$text" | rg --line-number "$pattern")"; then
    echo "::error title=Hot-path policy violation::$label"
    echo "Hot-path violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "::error title=Hot-path scanner failure::$label (rg exit $status)"
      echo "Hot-path guard failed to scan: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

reject 'global coroutine scope' '\bGlobalScope\b' --glob '*.kt' app/src
reject 'blocking coroutine bridge' '\brunBlocking\b' --glob '*.kt' app/src
reject 'sleep-based synchronization' '\bThread\s*\.\s*sleep\s*\(' --glob '*.kt' --glob '*.java' app/src
reject 'unbounded Java executor factory' \
  '\bExecutors\.(?:newCachedThreadPool|newFixedThreadPool|newSingleThreadExecutor)\s*\(' \
  --glob '*.kt' --glob '*.java' app/src/main
reject 'DataStore in camera session/runtime/preview/raw hot boundary' '\bDataStore\b|\.data\.first\s*\(' \
  --glob '*.kt' "$camera_root/session" "$camera_root/runtime" "$camera_root/preview" "$camera_root/raw"
reject 'network API in session startup boundary' '\b(?:HttpURLConnection|URLConnection|OkHttpClient|URL)\b' \
  --glob '*.kt' "$camera_root/session" "$camera_root/runtime"
reject 'frame-by-frame JSON formatting' '\b(?:Json|JSONObject|encodeToString)\b' \
  --glob '*.kt' "$camera_root/preview" "$camera_root/trace"

test -f "$preview_metrics" || { echo "Preview frame metrics file missing: $preview_metrics" >&2; exit 1; }
append_body="$(awk '
  /^[[:space:]]*fun recordSensorTimestamp\(timestampNs: Long\)/ { capture = 1 }
  capture && /^[[:space:]]*fun snapshot\(\): PreviewFrameMetricsSnapshot/ { exit }
  capture { print }
' "$preview_metrics")"
test -n "$append_body" || {
  echo 'Hot-path guard could not isolate recordSensorTimestamp().' >&2
  exit 1
}
reject_text 'preview frame append formatting/logging/JSON' \
  'String\.format|buildString|\.toString\s*\(|\b(?:Json|JSONObject|encodeToString)\b|\b(?:println|print)\s*\(|\bLog\.[A-Za-z]+\s*\(|\bTimber\.' \
  "$append_body"
reject_text 'preview frame append reactive UI/state emission' \
  '\b(?:MutableStateFlow|StateFlow|mutableStateOf|LiveData)\b|\.emit\s*\(' \
  "$append_body"
reject_text 'preview frame append collection growth or sorting' \
  '\b(?:ArrayList|HashMap|HashSet|mutableListOf|mutableMapOf|mutableSetOf|listOf|mapOf|setOf)\b|\.add\s*\(|\.sorted|\.sort\s*\(' \
  "$append_body"
reject_text 'preview frame append coroutine/thread dispatch' \
  '\b(?:launch|async|withContext|Thread|Executor|Channel)\b' \
  "$append_body"
reject 'boxed previous preview timestamp' 'previousTimestampNs\s*:\s*Long\?' "$preview_metrics"

for requirement in \
  'private val intervals: LongArray' \
  'private var previousTimestampNs = 0L' \
  'intervals[writeIndex] = intervalNs'; do
  if ! rg --fixed-strings --quiet "$requirement" "$preview_metrics"; then
    echo "Hot-path metrics requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Hot-path verification passed.'
