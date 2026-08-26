#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly prod="app/src/main/java/com/sahidcode404/camx/core/imaging/alignment"
readonly tests="app/src/test/java/com/sahidcode404/camx/core/imaging/alignment"
readonly doc="docs/computational-raw/M6_ALIGNMENT_VISIBILITY_UNCERTAINTY.md"
readonly adr="docs/adr/ADR-038-classical-alignment-visibility-uncertainty.md"

for path in \
  "$prod/AlignmentModel.kt" \
  "$prod/ReferenceAlignmentEngine.kt" \
  "$prod/AlignmentValidation.kt" \
  "$tests/M6AlignmentTestFixtures.kt" \
  "$tests/ReferenceAlignmentEngineTest.kt" \
  "$tests/AlignmentReservationTest.kt" \
  "$tests/AlignmentValidationTest.kt" \
  "$doc" \
  "$adr"; do
  test -f "$path" || { echo "M6 artifact missing: $path" >&2; exit 1; }
done

for requirement in \
  'data class AlignmentRequest' \
  'class AlignmentReservation private constructor' \
  'enum class FrameAlignmentDecision' \
  'data class FrameSupportSummary' \
  'data class PixelMeasurementSupport' \
  'data class RollingShutterMotionEvidence' \
  'data class AlignmentUncertainty' \
  'class AlignmentEvidenceSet internal constructor' \
  'object ReferenceAlignmentEngine' \
  'object AlignmentTruthEvaluator' \
  'p95TranslationErrorPixels' \
  'p99TranslationErrorPixels' \
  'catastrophicFraction'; do
  rg --fixed-strings --quiet "$requirement" "$prod" || {
    echo "M6 implementation requirement missing: $requirement" >&2
    exit 1
  }
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|androidx\.compose|tensorflow|onnx|mlkit|acquireLatestImage' \
  --glob '*.kt' "$prod"; then
  echo 'M6 alignment package crossed a forbidden camera/UI/AI boundary.' >&2
  exit 1
fi

for test_requirement in \
  'recoversKnownEvenTranslationDeterministically' \
  'ambiguousTextureFallsBackToReference' \
  'mixedQualitySetFallsBackToSmallerSubset' \
  'perPixelSupportMarksOcclusionWithoutInventingEvidence' \
  'rollingShutterEvidenceSeparatesTopAndBottomMotion' \
  'reservationBindsExactFrameSetAndRequest' \
  'unboundedEvaluationRequestFailsBeforeAlignment' \
  'oddTranslationSearchRadiusIsRejectedBecauseItChangesCfaPhase' \
  'syntheticTruthPassesZeroErrorPercentileGate' \
  'excludedCaseCountsAsCatastrophicFailure'; do
  rg --fixed-strings --quiet "$test_requirement" "$tests" || {
    echo "M6 acceptance test missing: $test_requirement" >&2
    exit 1
  }
done

rg --fixed-strings --quiet 'M6 does not claim exact-profile physical alignment acceptance from synthetic CI.' "$doc"
rg --fixed-strings --quiet 'integer translation in two-pixel increments' "$adr"
rg --fixed-strings --quiet 'Censored measurements never become inliers.' "$adr"

echo 'M6 alignment, visibility, occlusion, and uncertainty verification passed.'
