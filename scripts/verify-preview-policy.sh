#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly preview_dir="app/src/main/java/com/sahidcode404/camx/core/camera/preview"
readonly policy="$preview_dir/PreviewStreamPolicy.kt"
readonly geometry="$preview_dir/PreviewGeometryCalculator.kt"
failures=0

for required in "$policy" "$geometry"; do
  test -f "$required" || { echo "Preview policy file missing: $required" >&2; exit 1; }
done

if rg --line-number \
  'android\.hardware\.camera2\.|\b(CameraManager|CameraDevice|CameraCaptureSession)\b|\bopenCamera\s*\(|core\.camera\.(session|raw|topology|discovery)\.|core\.update\.|System\.loadLibrary|kotlinx\.coroutines|\bHandler(Thread)?\b|\bThread\s*\(' \
  "$policy" "$geometry"; then
  echo 'Preview policy purity violation: CAMX-104 policy/math must not depend on camera ownership, later camera subsystems, native/update code, or concurrency.' >&2
  failures=$((failures + 1))
fi

if rg --line-number '\b(1920|1080|1280|720|3840|2160|7680|4320)\b' "$preview_dir" --glob '*.kt'; then
  echo 'Preview policy universality violation: production preview code contains a hardcoded common preview resolution.' >&2
  failures=$((failures + 1))
fi

for requirement in \
  'MAX_PREVIEW_POLICY_CANDIDATES' \
  'PreviewFpsResolver.resolve(' \
  'PreviewGeometryCalculator.calculate(' \
  'PreviewPolicyResult.Unsupported' \
  'requestedType=' \
  'fpsResolved='; do
  if ! rg --fixed-strings --quiet "$requirement" "$policy"; then
    echo "Preview policy requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Preview policy verification passed.'
