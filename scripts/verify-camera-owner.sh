#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly owner="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraSessionController.kt"
readonly mutation_gate="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraStateMutationGate.kt"
readonly async_owner="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraAsyncOwnership.kt"
failures=0

for required_file in "$owner" "$mutation_gate" "$async_owner"; do
  test -f "$required_file" || { echo "Missing camera ownership file: $required_file" >&2; exit 1; }
done

open_calls="$(rg --line-number '\bopenCamera\s*\(' app/src/main native/core 2>/dev/null || true)"
if [[ -n "$open_calls" ]] && printf '%s\n' "$open_calls" | rg --quiet -v "^${owner}:"; then
  echo 'Camera ownership violation: openCamera outside CameraSessionController.' >&2
  printf '%s\n' "$open_calls" >&2
  failures=$((failures + 1))
fi
open_count="$(printf '%s\n' "$open_calls" | sed '/^$/d' | wc -l)"
if ((open_count != 1)); then
  echo "Camera ownership violation: expected exactly one openCamera call, found $open_count." >&2
  printf '%s\n' "$open_calls" >&2
  failures=$((failures + 1))
fi

ownership_imports="$(rg --line-number \
  '^import android\.hardware\.camera2\.(CameraDevice|CameraCaptureSession)(?:\s+as\s+\w+)?\s*$|^import android\.hardware\.camera2\.\*' \
  app/src/main/java 2>/dev/null || true)"
if [[ -n "$ownership_imports" ]] && printf '%s\n' "$ownership_imports" | rg --quiet -v "^${owner}:"; then
  echo 'Camera ownership violation: CameraDevice/session type outside CameraSessionController.' >&2
  printf '%s\n' "$ownership_imports" >&2
  failures=$((failures + 1))
fi

qualified_ownership_uses="$(rg --line-number \
  'android\.hardware\.camera2\.(CameraDevice|CameraCaptureSession)\b' \
  app/src/main/java 2>/dev/null || true)"
if [[ -n "$qualified_ownership_uses" ]] && \
  printf '%s\n' "$qualified_ownership_uses" | rg --quiet -v "^${owner}:"; then
  echo 'Camera ownership violation: fully qualified device/session use outside the sole owner.' >&2
  printf '%s\n' "$qualified_ownership_uses" >&2
  failures=$((failures + 1))
fi

if rg --line-number \
  '^import android\.hardware\.camera2\.(CameraManager|CameraDevice|CameraCaptureSession)(?:\s+as\s+\w+)?\s*$|^import android\.hardware\.camera2\.\*|android\.hardware\.camera2\.(CameraManager|CameraDevice|CameraCaptureSession)\b' \
  app/src/main/java/com/sahidcode404/camx/feature app/src/main/java/com/sahidcode404/camx/ui \
  app/src/main/java/com/sahidcode404/camx/MainActivity.kt 2>/dev/null; then
  echo 'Camera ownership violation: UI imports Camera2 ownership types.' >&2
  failures=$((failures + 1))
fi

for requirement in \
  'class CameraSessionController' \
  'private val mutationGate = CameraStateMutationGate(callbackDispatcher)' \
  'private val asyncOwnership = CameraAsyncOwnership()' \
  'CameraGenerationGate' \
  'HandlerThread("camx-camera-control")' \
  'cameraManager.openCamera(' \
  'camera.createCaptureSession(' \
  'CameraDevice.TEMPLATE_PREVIEW' \
  'setRepeatingRequest(' \
  'PendingCameraStage.FIRST_FRAME'; do
  if ! rg --fixed-strings --quiet "$requirement" "$owner"; then
    echo "Camera ownership requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

if rg --line-number '\boperationMutex\b|\bMutex\s*\(' "$owner"; then
  echo 'Camera ownership violation: CameraSessionController must use the non-suspending mutation gate, not an owner-level coroutine mutex.' >&2
  failures=$((failures + 1))
fi

for requirement in \
  'class CameraStateMutationGate' \
  'suspend fun <T> mutate(block: () -> T): T' \
  'mutex.withLock { block() }'; do
  if ! rg --fixed-strings --quiet "$requirement" "$mutation_gate"; then
    echo "Camera mutation-gate requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done
if rg --line-number 'mutate\s*\(\s*block\s*:\s*suspend|block\s*:\s*suspend\s*\(' "$mutation_gate"; then
  echo 'Camera ownership violation: authoritative mutation block must remain non-suspending.' >&2
  failures=$((failures + 1))
fi

for requirement in \
  'class PendingCameraOperationPermit' \
  'permit.ownerIdentity === ownerIdentity' \
  'permit.intent == currentIntent' \
  'pending[permit.stage] === permit' \
  'fun <T> resolveResource(' \
  'ResourceAdoption.Stale(delivered.detachForStaleCleanup())' \
  'class CameraResourceCleanup' \
  'class CameraCleanupPlan'; do
  if ! rg --fixed-strings --quiet "$requirement" "$async_owner"; then
    echo "Async camera ownership requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done
if rg --line-number '\badoptOrClose\b|\bcloseIfUnadopted\b' "$async_owner"; then
  echo 'Camera ownership violation: stale resource cleanup must be detached under the gate and executed after unlock.' >&2
  failures=$((failures + 1))
fi

readonly state_file="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraEngineState.kt"
for state in Closed WaitingForSurface Opening ConfiguringPreview Previewing Switching \
  ConfiguringRaw CapturingRaw PairingRaw WritingDng RestoringPreview Pausing \
  RecoverableError StructuralError; do
  if ! rg --quiet "(?:object|class) ${state}\b" "$state_file"; then
    echo "Camera state missing: $state" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Camera owner verification passed.'
