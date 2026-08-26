#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly prod="app/src/main/java/com/sahidcode404/camx/core/imaging/calibration"
readonly tests="app/src/test/java/com/sahidcode404/camx/core/imaging/calibration"
readonly implementation_doc="docs/computational-raw/M5_CALIBRATION_NOISE_MODEL.md"
readonly adr="docs/adr/ADR-037-calibration-noise-reference-model.md"

for path in \
  "$prod/CalibrationModel.kt" \
  "$prod/ReferenceCalibrationEngine.kt" \
  "$prod/CalibrationValidation.kt" \
  "$tests/M5CalibrationTestFixtures.kt" \
  "$tests/CalibrationModelTest.kt" \
  "$tests/ReferenceCalibrationEngineTest.kt" \
  "$tests/CalibrationValidationTest.kt" \
  "$implementation_doc" \
  "$adr"; do
  test -f "$path" || { echo "M5 artifact missing: $path" >&2; exit 1; }
done

for requirement in \
  'class M5CalibrationProfile' \
  'data class CalibrationConfidenceVector' \
  'data class NoiseParameters' \
  'fun varianceForSignalDn' \
  'class ColorMatrixCalibration' \
  'class CalibrationReservation private constructor' \
  'class CalibratedMeasurementFrame internal constructor' \
  'object ReferenceCalibrationEngine' \
  'fun calibrate(' \
  'class CalibrationValidationCorpus' \
  'data class CalibrationValidationReport' \
  'val darkRmseDn: Double' \
  'val flatNormalizedRmse: Double' \
  'val linearityNormalizedRmse: Double' \
  'LITTLE_ENDIAN_16' \
  'sourceCanonicalSha256'; do
  if ! rg --fixed-strings --quiet "$requirement" "$prod"; then
    echo "M5 implementation requirement missing: $requirement" >&2
    exit 1
  fi
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|androidx\.compose|tensorflow|onnx|mlkit' \
  --glob '*.kt' "$prod"; then
  echo 'M5 calibration package crossed a forbidden camera/UI/AI boundary.' >&2
  exit 1
fi

for test_requirement in \
  'profileRequiresExactBlackWhiteOrdering' \
  'confidenceDimensionsRemainSeparate' \
  'colorMatrixRequiresIlluminantAndNonSingularMatrix' \
  'noiseVarianceIncludesShotReadAndFpnTerms' \
  'profileDigestIsDeterministicAndSensitive' \
  'referenceCalibrationDecodesLittleEndianAndPreservesCensoring' \
  'profileIdentityMismatchFailsClosedBeforeCopy' \
  'reservationRejectsInsufficientResidentBudget' \
  'calibratedFrameSetFreezesInputRaster' \
  'syntheticDarkFlatLinearityCorpusPasses' \
  'badDarkResidualFails' \
  'insufficientNoiseConfidenceFails' \
  'validationCorpusIsDefensivelyFrozen'; do
  if ! rg --fixed-strings --quiet "$test_requirement" "$tests"; then
    echo "M5 acceptance test missing: $test_requirement" >&2
    exit 1
  fi
done

rg --fixed-strings --quiet 'There is deliberately no generic scalar confidence.' "$implementation_doc"
rg --fixed-strings --quiet 'M5 does not claim any exact physical profile is calibrated by CI.' "$implementation_doc"
rg --fixed-strings --quiet 'No single generic confidence scalar replaces these meanings.' "$adr"
rg --fixed-strings --quiet 'AI is forbidden in M5' "$adr"

echo 'M5 calibration and noise reference verification passed.'
