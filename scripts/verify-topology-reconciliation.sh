#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly topology_root="app/src/main/java/com/sahidcode404/camx/core/camera/topology"
readonly resolver="$topology_root/CameraTopologyResolver.kt"
readonly reconciler="$topology_root/PostFirstFrameTopologyReconciler.kt"
readonly aux_orchestrator="$topology_root/PostFirstFrameAuxDiscoveryOrchestrator.kt"
readonly graph="app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewGraph.kt"
readonly ndk_adapter="native/core/src/discovery/android_ndk_advertised_metadata.cpp"
readonly cmake="native/core/CMakeLists.txt"
failures=0

require_fixed() {
  local label="$1"
  local value="$2"
  local file="$3"
  if ! rg --fixed-strings --quiet -- "$value" "$file"; then
    echo "CAMX-107 topology requirement missing: $label ($value)" >&2
    failures=$((failures + 1))
  fi
}

reject() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number "$pattern" "$@" 2>/dev/null)"; then
    echo "::error title=CAMX-107 topology boundary violation::$label"
    echo "CAMX-107 topology boundary violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "CAMX-107 topology scanner failed: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

for requirement in \
  'MAX_TOTAL_EVIDENCE = 256' \
  'MAX_ROUTES = 128' \
  'MAX_PROFILES = 128' \
  'MAX_CANONICAL_LENSES = 64' \
  'MAX_PROFILES_PER_LENS = 32' \
  'MAX_PROVENANCE_SOURCES = 5' \
  'MAX_PREVIEW_STREAMS = 128' \
  'MAX_FPS_RANGES = 64' \
  'MAX_RAW_SIZES = 64' \
  'previousTrustedTopology' \
  'metadataConflicts' \
  'clusterCanFormRoute' \
  'toRawBits()'; do
  require_fixed 'bounded deterministic resolver' "$requirement" "$resolver"
done

for requirement in \
  'Dispatchers.Default' \
  'repository.beginReconciliation(environment)' \
  'repository.publish(resolved, permit)' \
  'CameraTopologyResolver.resolve' \
  'AtomicBoolean(false)'; do
  require_fixed 'post-first-frame reconciliation' "$requirement" "$reconciler"
done

for requirement in \
  'DeepAuxScanPolicy.decide' \
  'DeepAuxScanState.HOT_ONLY' \
  'DeepAuxScanState.FULL_RECONCILIATION' \
  'boundedCameraMap(Level2Lane.values().toList(), 2)' \
  'includeNearbyCandidates = !hotOnly' \
  'includeLowNamespaceCandidates = !hotOnly'; do
  require_fixed 'Checkpoint-C Level-2 to Level-4 orchestration' "$requirement" "$aux_orchestrator"
done

for requirement in \
  'AndroidAdvertisedCameraEvidenceBackend' \
  'NdkAdvertisedCameraEvidenceBackend' \
  'PostFirstFrameAuxDiscoveryOrchestrator' \
  'state.firstFrameVerified' \
  'topologyReconciler.startAfterFirstFrame()'; do
  require_fixed 'visible-preview topology hook' "$requirement" "$graph"
done

for requirement in \
  'DynamicLibrary library("libcamera2ndk.so")' \
  'dlsym' \
  'kCameraNdkMinimumApi = 24' \
  'ACameraManager_getCameraCharacteristics'; do
  require_fixed 'runtime-only Camera NDK metadata' "$requirement" "$ndk_adapter"
done

reject 'Camera2 ownership in topology policy' \
  'android\.hardware\.camera2|\bCameraManager\b|\bCameraDevice\b|\bCameraCaptureSession\b|\bopenCamera\s*\(|\bcreateCaptureSession\b|\bsetRepeatingRequest\b|\bcapture\s*\(' \
  "$topology_root"
reject 'session-owner dependency in topology reconciliation' \
  '^import com\.sahidcode404\.camx\.core\.camera\.session\.' \
  "$topology_root"
reject 'device-specific topology routing' \
  'Build\.(?:MANUFACTURER|MODEL|BRAND|DEVICE|HARDWARE|SOC_MANUFACTURER|SOC_MODEL)|manufacturer|deviceModel|socModel|sensorVendor' \
  "$resolver" "$reconciler" "$aux_orchestrator"
reject 'numeric or string-shape camera ID role logic' \
  '(?:toIntOrNull|startsWith|endsWith|substring).*?(?:transport|camera|physical)|(?:transport|camera|physical).*?(?:toIntOrNull|startsWith|endsWith|substring)' \
  "$resolver" "$reconciler" "$aux_orchestrator"
reject 'native Camera NDK control plane' \
  '\bACameraManager_openCamera\b|\bACameraDevice\b|\bACameraCaptureSession\b|\bACaptureRequest\b' \
  "$ndk_adapter" native/core/src/discovery native/core/include/camx/ndk_advertised_metadata.hpp
reject 'RAW capture implementation in CAMX-107' \
  '\bImageReader\b|\bAImageReader\b|\bDngCreator\b|\bRAW_SENSOR\b.*(?:capture|request)' \
  "$topology_root" app/src/main/java/com/sahidcode404/camx/core/camera/discovery

if rg --line-number '^[[:space:]]*[^#].*camera2ndk' "$cmake"; then
  echo 'CAMX-107 topology boundary violation: libcamx_core must not strongly link camera2ndk.' >&2
  failures=$((failures + 1))
fi

((failures == 0)) || exit 1
echo 'CAMX-107 topology reconciliation verification passed.'
