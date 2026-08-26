#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly discovery_root="app/src/main/java/com/sahidcode404/camx/core/camera/discovery"
readonly seed_source="$discovery_root/AndroidPublicCameraSeedSource.kt"
readonly seed_resolver="$discovery_root/MinimalFirstInstallSeedDiscovery.kt"
failures=0

reject_in() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number "$pattern" --glob '*.kt' "$@" 2>/dev/null)"; then
    echo "::error title=Discovery purity violation::$label"
    echo "Discovery purity violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "Discovery purity scanner failed: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

# Every discovery backend remains metadata-only and non-owning.
reject_in 'camera device/session ownership API' \
  '\bCameraDevice\b|\bCameraCaptureSession\b|\bopenCamera\s*\(|\bcreateCaptureSession\b|\bsetRepeating(?:Request|Burst)\b|\bcapture(?:Burst)?\s*\(' \
  "$discovery_root"
reject_in 'camera-owner implementation dependency' \
  '^import com\.sahidcode404\.camx\.core\.camera\.(?:session|runtime|raw)\.' \
  "$discovery_root"
reject_in 'update or OTA dependency' '^import com\.sahidcode404\.camx\.core\.update\.' "$discovery_root"
reject_in 'unbounded discovery concurrency' \
  '\bGlobalScope\b|\bExecutors\b|\bnewFixedThreadPool\b|\bThread\s*\(|\basync\s*\{|\blaunch\s*\{' \
  "$discovery_root"

# The frozen CAMX-102 first-install seed path must stay minimal even though CAMX-107 adds separate
# ADVERTISED/DEEP metadata backends in the same package.
reject_in 'seed depends on native or deep discovery' \
  '^import com\.sahidcode404\.camx\.core\.camera\.diagnostics\.Native|\bSystem\.loadLibrary\s*\(' \
  "$seed_source" "$seed_resolver"
reject_in 'seed performs complete stream RAW or physical enumeration' \
  '\bgetOutputFormats\s*\(|\bgetHighResolutionOutputSizes\s*\(|\bgetHighSpeedVideo|\bRAW_SENSOR\b|REQUEST_AVAILABLE_CAPABILITIES_RAW|physicalCameraIds' \
  "$seed_source" "$seed_resolver"

for requirement in \
  'cameraManager.cameraIdList' \
  'cameraManager.getCameraCharacteristics(transportId.value)' \
  'CameraCharacteristics.LENS_FACING' \
  'CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS' \
  'CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE' \
  'CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES' \
  'CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP' \
  'getOutputSizes(SurfaceHolder::class.java)' \
  'SEED_MAX_ADVERTISED_IDS = 64' \
  'SEED_MAX_FOCAL_LENGTHS = 16' \
  'metadataTrust = CameraTrust.ADVERTISED'; do
  if ! rg --fixed-strings --quiet "$requirement" "$seed_source" "$seed_resolver"; then
    echo "CAMX-102 discovery requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Discovery purity verification passed.'
