#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly production="app/src/main/java"
readonly controller="$production/com/sahidcode404/camx/core/camera/session/CameraSessionController.kt"
readonly projector="$production/com/sahidcode404/camx/core/camera/lens/CameraLensUiProjector.kt"
readonly coordinator="$production/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewCoordinator.kt"
readonly feature="$production/com/sahidcode404/camx/feature"

open_count="$(rg -n --glob '*.kt' '\.openCamera\s*\(' "$production" | wc -l | tr -d ' ')"
if [[ "$open_count" != "1" ]]; then
  echo "Lens-test guard requires exactly one production openCamera call; found $open_count." >&2
  exit 1
fi
rg --quiet '\.openCamera\s*\(' "$controller" || {
  echo 'The sole openCamera call must remain in CameraSessionController.kt.' >&2
  exit 1
}

if rg --quiet '^import android\.hardware\.camera2\.(CameraManager|CameraDevice|CameraCaptureSession)' "$feature"; then
  echo 'Feature UI must not import Camera2 ownership types.' >&2
  exit 1
fi
if rg --quiet '\b(openCameraId|physicalCameraId|CameraTransportId|PhysicalCameraId)\b' "$feature"; then
  echo 'Feature UI must not access or display camera transport/physical identifiers.' >&2
  exit 1
fi
if rg --quiet '^import android\.' "$projector"; then
  echo 'CameraLensUiProjector must remain pure and Android-free.' >&2
  exit 1
fi
ui_model="$(awk '/data class CameraLensUiItem\(/ { capture=1 } capture { print } capture && /^\)/ { exit }' "$projector")"
if grep -Eq '\b(CameraTransportId|PhysicalCameraId|openCameraId|physicalCameraId)\b' <<<"$ui_model"; then
  echo 'CameraLensUiItem must not expose raw camera identifiers.' >&2
  exit 1
fi

for file in "$projector" "$coordinator" "$feature"; do
  if rg --quiet 'Build\.(MANUFACTURER|MODEL|SOC_MODEL|SOC_MANUFACTURER)|manufacturer\s*==|model\s*==|soc\s*==' "$file"; then
    echo "Device/manufacturer/SoC routing is forbidden in lens-test policy: $file" >&2
    exit 1
  fi
done

if rg --quiet 'forEachIndexed|withIndex\(\).*lens|\blens(Index|Position)\b' "$projector" "$feature"; then
  echo 'List-index-based lens identity/labeling is forbidden.' >&2
  exit 1
fi
if rg --quiet '"(?:camera|rear) [0-9]+"|"[0-9]+"\s*(?:toString\(\))?\s*//.*lens' "$projector" "$feature"; then
  echo 'Numeric camera-ID labels are forbidden in lens UI.' >&2
  exit 1
fi

for requirement in \
  'OutputConfiguration' \
  'setPhysicalCameraId' \
  'createCaptureSessionByOutputConfigurations' \
  'Build.VERSION.SDK_INT < Build.VERSION_CODES.P' \
  'createCaptureSession('; do
  rg --fixed-strings --quiet "$requirement" "$controller" || {
    echo "Physical/direct preview owner requirement missing: $requirement" >&2
    exit 1
  }
done

for requirement in \
  'CameraLensUiProjector' \
  'selectLens' \
  'session.pause()' \
  'surfacePort.awaitSurface()' \
  'firstFrameVerified'; do
  rg --fixed-strings --quiet "$requirement" "$coordinator" || {
    echo "Lens switching orchestration requirement missing: $requirement" >&2
    exit 1
  }
done

if rg --quiet '\b(ImageReader|DngCreator|RAW_SENSOR|AIMAGE_FORMAT_RAW|RawCapture)\b' "$projector" "$coordinator" "$feature"; then
  echo 'RAW implementation is outside the AUX lens test checkpoint.' >&2
  exit 1
fi

./scripts/verify-camera-owner.sh >/dev/null

echo 'AUX lens-test UI and physical-target boundary verification passed.'
