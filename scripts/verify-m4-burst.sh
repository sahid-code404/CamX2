#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly models="app/src/main/java/com/sahidcode404/camx/core/camera/raw/RawBurstModels.kt"
readonly pairer="app/src/main/java/com/sahidcode404/camx/core/camera/raw/RawBurstTimestampPairer.kt"
readonly controller="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraSessionController.kt"
readonly output_plan="app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraOutputPlan.kt"
readonly model_tests="app/src/test/java/com/sahidcode404/camx/core/camera/raw/RawBurstModelsTest.kt"
readonly pairer_tests="app/src/test/java/com/sahidcode404/camx/core/camera/raw/RawBurstTimestampPairerTest.kt"
readonly output_tests="app/src/test/java/com/sahidcode404/camx/core/camera/session/CameraOutputPlanTest.kt"
readonly implementation_doc="docs/computational-raw/M4_BOUNDED_BURST_ACQUISITION.md"
readonly adr="docs/adr/ADR-036-bounded-raw-burst-transaction.md"

for path in \
  "$models" \
  "$pairer" \
  "$controller" \
  "$output_plan" \
  "$model_tests" \
  "$pairer_tests" \
  "$output_tests" \
  "$implementation_doc" \
  "$adr"; do
  test -f "$path" || { echo "M4 artifact missing: $path" >&2; exit 1; }
done

for requirement in \
  'MAX_FRAMES = 16' \
  'class RawBurstReservation private constructor' \
  'val maxSourceBytesPerFrame: Long' \
  'val requiredResidentBytes: Long' \
  'class ImmutableRawFrameSet internal constructor' \
  'class RawBurstTimestampPairer' \
  'data class Captured(val frameSet: ImmutableRawFrameSet)' \
  'CameraRequestLifetime.BOUNDED_BURST' \
  'fun temporaryRawBurst(' \
  'internal suspend fun captureRawBurst(' \
  'session.captureBurst(' \
  'source.acquireNextImage()' \
  'withContext(Dispatchers.Default)' \
  'require(sourceRequired <= reservation.maxSourceBytesPerFrame)'; do
  if ! rg --fixed-strings --quiet "$requirement" "$models" "$pairer" "$controller" "$output_plan"; then
    echo "M4 implementation requirement missing: $requirement" >&2
    exit 1
  fi
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraManager|CameraDevice|CameraCaptureSession|ImageReader' \
  "$models" "$pairer"; then
  echo 'M4 immutable burst contracts/pairer crossed the Camera2 ownership boundary.' >&2
  exit 1
fi

if rg --line-number 'acquireLatestImage' "$controller" "$models" "$pairer"; then
  echo 'M4 forbids acquireLatestImage-style silent sensor-evidence loss.' >&2
  exit 1
fi

for test_requirement in \
  'reservationProvesSourceCopyMetadataAndSafetyPeak' \
  'reservationRejectsInsufficientBudgetBeforeCapture' \
  'frameDefensivelyFreezesCanonicalRaster' \
  'immutableFrameSetRequiresExactContiguousMembership' \
  'duplicateSensorTimestampIsRejected' \
  'outOfOrderCallbacksProduceExactOrdinalOrder' \
  'duplicateResultOrdinalFailsClosedAndReleasesPendingImages' \
  'duplicateImageTimestampClosesBothStillOwnedImagesExactlyOnce' \
  'closingIncompleteBurstNeverReturnsPartialFrameSet' \
  'imageOwnershipMovesAtMostOnce' \
  'temporaryRawBurstPlanIsExplicitlyBoundedAndNeverRepeating'; do
  if ! rg --fixed-strings --quiet "$test_requirement" "$model_tests" "$pairer_tests" "$output_tests"; then
    echo "M4 acceptance test missing: $test_requirement" >&2
    exit 1
  fi
done

rg --fixed-strings --quiet 'exact-profile physical burst acceptance remains a separate evidence gate' "$implementation_doc"
rg --fixed-strings --quiet 'No incomplete frame set is a valid fallback.' "$adr"
rg --fixed-strings --quiet 'M4 is not physically certified by CI.' "$adr"
rg --fixed-strings --quiet 'CameraSessionController' "$adr"
rg --fixed-strings --quiet 'MAX_FRAMES` is an implementation safety ceiling, not a fixed scientific recipe' "$adr"

echo 'M4 bounded RAW burst acquisition verification passed.'
