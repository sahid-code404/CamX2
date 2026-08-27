#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

required=(
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt
  app/src/main/java/com/sahidcode404/camx/core/camera/raw/Cp4ComputationalDngStore.kt
  app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/Cp1CaptureCoordinator.kt
  app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriterTest.kt
)
for path in "${required[@]}"; do
  test -f "$path" || { echo "Missing CP4 artifact: $path" >&2; exit 1; }
done

grep -q 'class Cp4ComputationalDngWriter' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt
grep -q 'Cp4ComputationalDngStore' app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/Cp1CaptureCoordinator.kt
grep -q 'cp4Store.save' app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/Cp1CaptureCoordinator.kt
grep -q 'MediaStoreTransaction' app/src/main/java/com/sahidcode404/camx/core/camera/raw/Cp4ComputationalDngStore.kt
grep -q 'SENSOR_COLOR_TRANSFORM1' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt
grep -q 'fixedPatternNoiseMode' app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt
grep -q 'oddActiveOriginShiftsExportedCfaPhaseWithoutRemosaic' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriterTest.kt
grep -q 'missingCamera2ColorAuthorityFailsClosed' app/src/test/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriterTest.kt

if rg -n 'android\.hardware\.camera2|android\.media\.Image|CameraManager|CameraDevice|ImageReader|DngCreator' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt; then
  echo 'CP4 fused-DNG writer must not own Camera2, ImageReader, Image, or DngCreator.' >&2
  exit 1
fi

if rg -n 'fixedPattern(Fraction|Noise).*=[[:space:]]*(0\.|[1-9])|fabricat(e|ed|ing).*noise' \
  app/src/main/java/com/sahidcode404/camx/core/imaging/interchange/Cp4ComputationalDngWriter.kt; then
  echo 'CP4 must not fabricate a fixed-pattern-noise calibration term.' >&2
  exit 1
fi

echo 'CP4 computational DNG production integration verification passed.'
