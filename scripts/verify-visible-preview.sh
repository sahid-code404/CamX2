#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly main="app/src/main/java/com/sahidcode404/camx/MainActivity.kt"
readonly screen="app/src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt"
readonly stable="app/src/main/java/com/sahidcode404/camx/ui/components/StableSurfaceView.kt"
readonly graph="app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewGraph.kt"
readonly coordinator="app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewCoordinator.kt"
readonly reader="app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/SelectedSeedPreviewCapabilityReader.kt"
readonly provider="app/src/main/java/com/sahidcode404/camx/core/camera/preview/PreviewSurfaceProvider.kt"
failures=0

for file in "$main" "$screen" "$stable" "$graph" "$coordinator" "$reader" "$provider"; do
  test -f "$file" || { echo "Visible-preview integration file missing: $file" >&2; exit 1; }
done

require_text() {
  local file="$1"
  local text="$2"
  if ! rg --fixed-strings --quiet "$text" "$file"; then
    echo "Visible-preview requirement missing from $file: $text" >&2
    failures=$((failures + 1))
  fi
}

reject() {
  local label="$1"
  local pattern="$2"
  shift 2
  local matches
  if matches="$(rg --line-number "$pattern" "$@" 2>/dev/null)"; then
    echo "Visible-preview integration violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "Visible-preview guard failed to scan: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

# Activity lifetime and recomposition must not create duplicate production graphs/controllers.
require_text "$main" 'private lateinit var visiblePreviewGraph: VisiblePreviewGraph'
require_text "$main" 'visiblePreviewGraph = VisiblePreviewGraph(this)'
require_text "$main" 'visiblePreviewGraph.coordinator.resume(currentDisplayRotation())'
require_text "$main" 'visiblePreviewGraph.coordinator.pause()'
require_text "$main" 'visiblePreviewGraph.close()'
graph_constructions="$(rg --fixed-strings 'VisiblePreviewGraph(this)' "$main" | wc -l)"
if ((graph_constructions != 1)); then
  echo "Visible-preview graph construction violation: expected exactly one Activity construction, found $graph_constructions." >&2
  failures=$((failures + 1))
fi

# The actual SurfaceView and host are remembered once across Compose recomposition.
require_text "$stable" 'val host = remember(context)'
require_text "$stable" 'val surfaceView = remember(host)'
require_text "$stable" 'surfaceView.holder.setFixedSize(bufferSize.width, bufferSize.height)'
require_text "$stable" 'viewSize = IntSize(viewWidth, viewHeight)'
require_text "$stable" 'bufferSize = IntSize(reportedBufferWidth, reportedBufferHeight)'
require_text "$screen" 'bufferSize = renderSpec?.bufferSize'
require_text "$screen" 'geometry = renderSpec?.geometry'
require_text "$screen" 'onSurfaceAvailable = onSurfaceAvailable'
require_text "$screen" 'onSurfaceDestroyed = onSurfaceDestroyed'

# Production startup must traverse existing seed/policy/provider/controller boundaries.
require_text "$graph" 'AndroidFirstInstallSeedDiscovery('
require_text "$graph" 'AndroidSelectedSeedPreviewCapabilityReader(cameraManager)'
require_text "$graph" 'private val controller = CameraSessionController(cameraManager)'
require_text "$graph" 'controller.startPreview('
require_text "$coordinator" 'PreviewStreamPolicy::resolve'
require_text "$coordinator" 'surfacePort.awaitBufferSize(lease.identity, supported.configuration.size)'
require_text "$coordinator" 'firstFrameVerified = state.firstFrameVerified'
require_text "$provider" 'class GenerationSafePreviewSurfaceProvider'
controller_starts="$(rg --line-number 'controller\.startPreview\s*\(' "$graph" | wc -l)"
if ((controller_starts != 1)); then
  echo "Visible-preview controller wiring violation: expected one graph startPreview delegation, found $controller_starts." >&2
  failures=$((failures + 1))
fi

# Selected-route capability expansion stays bounded and metadata-only.
for requirement in \
  'VISIBLE_PREVIEW_MAX_STREAMS = 128' \
  'VISIBLE_PREVIEW_MAX_FPS_RANGES = 64' \
  'getOutputSizes(SurfaceHolder::class.java)' \
  'getOutputMinFrameDuration(SurfaceHolder::class.java, size)' \
  'CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES' \
  'CameraCharacteristics.SENSOR_ORIENTATION'; do
  require_text "$reader" "$requirement"
done
reject 'selected-route capability reader owns or opens a camera' \
  '\bCameraDevice\b|\bCameraCaptureSession\b|\bopenCamera\s*\(|\bcreateCaptureSession\s*\(|\bsetRepeatingRequest\s*\(' \
  "$reader"
reject 'selected-route reader expanded into AUX/RAW/high-speed topology' \
  '\bphysicalCameraIds\b|\bRAW_SENSOR\b|REQUEST_AVAILABLE_CAPABILITIES_RAW|\bImageReader\b|\bgetHighResolutionOutputSizes\b|\bgetHighSpeedVideo' \
  "$reader"

# Feature/UI code can carry Surface and immutable state, never Camera2 ownership types.
reject 'feature/UI owns Camera2 resources' \
  'android\.hardware\.camera2\.|\bCameraDevice\b|\bCameraCaptureSession\b|\bCameraManager\b|\bopenCamera\s*\(' \
  "$main" app/src/main/java/com/sahidcode404/camx/feature app/src/main/java/com/sahidcode404/camx/ui

# No common fixed preview resolution may become bootstrap/UI policy. Tests may use concrete dimensions.
reject 'hardcoded common preview resolution in visible-preview production wiring' \
  '\b(?:1920|1080|1280|720|3840|2160|7680|4320)\b' \
  "$main" "$screen" "$stable" "$graph" "$coordinator" "$reader"

((failures == 0)) || exit 1
echo 'Visible-preview integration verification passed.'
