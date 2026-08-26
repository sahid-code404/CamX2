#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

required=(
  app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
  app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngine.kt
  app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionValidation.kt
  app/src/test/java/com/sahidcode404/camx/core/imaging/reconstruction/M7ReconstructionTestFixtures.kt
  app/src/test/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngineTest.kt
  docs/computational-raw/M7_COMPUTATIONAL_PHOTO_RECONSTRUCTION.md
)
for path in "${required[@]}"; do
  test -f "$path" || { echo "Missing M7 artifact: $path" >&2; exit 1; }
done

grep -q 'class FusedCfaRadiance' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
grep -q 'GraphRepresentation.FUSED_CFA_RADIANCE' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
grep -q 'PhotometricDomain.LINEAR_SENSOR_RADIANCE' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
grep -q 'GraphUncertaintySemantics.FULL_RECONSTRUCTION_UNCERTAINTY' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
grep -q 'class ReconstructionReservation' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReconstructionModel.kt
grep -q 'object ReferenceReconstructionEngine' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngine.kt
grep -q 'inverse-variance-v1' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngine.kt
grep -q 'measurementBindingSha256' app/src/main/java/com/sahidcode404/camx/core/imaging/alignment/AlignmentModel.kt
grep -q 'learnedPriorChangedPixels = false' app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngine.kt
grep -q 'symmetricNoiseImprovesAgainstSameSingleRaw' app/src/test/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngineTest.kt
grep -q 'occludedMeasurementCannotGhostReferenceGrid' app/src/test/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngineTest.kt
grep -q 'foreignAlignmentEvidenceIsRejectedByExactMeasurementBinding' app/src/test/java/com/sahidcode404/camx/core/imaging/reconstruction/ReferenceReconstructionEngineTest.kt

if rg -n 'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|DngCreator' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction; then
  echo 'M7 reconstruction must not own Camera2/ImageReader or write DNG.' >&2
  exit 1
fi

if rg -n 'demosaic|remosaic|tone.?map|saturation|contrast|artistic|super.?resolution' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/reconstruction; then
  echo 'M7 scalar negative production contains a forbidden rendering/grid-mutation token.' >&2
  exit 1
fi

echo 'M7 computational photo reconstruction reference verification passed.'
