#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

required=(
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngModel.kt
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriter.kt
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngInspector.kt
  app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriterTest.kt
  docs/computational-raw/M8B_COMPUTATIONAL_DNG_INTERCHANGE.md
  docs/adr/ADR-039-direct-computational-dng-reference-prototype.md
)
for path in "${required[@]}"; do
  test -f "$path" || { echo "Missing M8B artifact: $path" >&2; exit 1; }
done

grep -q 'class ComputationalDngWriter' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriter.kt
grep -q 'class ComputationalDngReservation' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngModel.kt
grep -q 'PHOTOMETRIC_CFA = 32803' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngInspector.kt
grep -q 'SAMPLE_FORMAT_IEEE_FLOAT = 3' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriter.kt
grep -q 'TAG_COLOR_MATRIX_1 = 50721' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriter.kt
grep -q 'byteArrayOf(1, 4, 0, 0)' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriter.kt
grep -q 'MAX_IFD_ENTRIES' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngModel.kt
grep -q 'DNGPrivateData' docs/computational-raw/M8B_COMPUTATIONAL_DNG_INTERCHANGE.md
grep -q 'deterministicFloatCfaWriteRoundTripsM7RadianceAndUncertainty' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriterTest.kt
grep -q 'missingColorCalibrationIsRejectedWithoutFabricatingMetadata' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriterTest.kt
grep -q 'malformedStripOffsetIsRejectedByBoundedInspector' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriterTest.kt
grep -q 'foreignCalibrationDigestIsRejectedBeforeWriting' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/ComputationalDngWriterTest.kt

if rg -n 'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|android\.hardware\.camera2\.DngCreator|\bDngCreator\b' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange; then
  echo 'M8B computational interchange must not own Camera2/ImageReader or reuse Android DngCreator.' >&2
  exit 1
fi

if rg -n -i '\b(fun|class|object)\s+(demosaic|remosaic|tonemap|render)\b|\b(demosaic|remosaic|tonemap|render)\s*\(' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange; then
  echo 'M8B computational interchange contains executable rendering or remosaic logic.' >&2
  exit 1
fi

echo 'M8B computational DNG/interchange reference verification passed.'
