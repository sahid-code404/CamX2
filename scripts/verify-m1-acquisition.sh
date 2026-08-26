#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly source_root='app/src/main/java/com/sahidcode404/camx/core/camera/acquisition'
readonly test_root='app/src/test/java/com/sahidcode404/camx/core/camera/acquisition'
readonly architecture_doc='docs/computational-raw/M1_ACQUISITION_IMPLEMENTATION.md'

for file in \
  "$source_root/AcquisitionModel.kt" \
  "$source_root/CanonicalRaster.kt" \
  "$source_root/AcquisitionHandoff.kt" \
  "$source_root/RawContextBridge.kt" \
  "$source_root/SourceCorpus.kt" \
  "$test_root/AcquisitionModelTest.kt" \
  "$test_root/CanonicalRasterHasherTest.kt" \
  "$test_root/AcquisitionHandoffTest.kt" \
  "$test_root/SourceCorpusTest.kt" \
  "$architecture_doc"; do
  test -s "$file" || { echo "M1 acquisition artifact missing: $file" >&2; exit 1; }
done

for token in \
  'sealed interface InterpretableSensorDomain' \
  'sealed interface CameraProcessed' \
  'sealed interface OpaqueTransport' \
  'object MosaicSensorSamples' \
  'object RawPrivateToken' \
  'class AcquisitionHandoffGate' \
  'class AcquisitionLease' \
  'object CanonicalRasterHasher' \
  'class BoundedSourceCorpusBuilder' \
  'fun RawCaptureContext.toAcquisitionIdentity'; do
  rg --fixed-strings --quiet "$token" "$source_root" || {
    echo "M1 acquisition contract missing: $token" >&2
    exit 1
  }
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader' \
  "$source_root" --glob '*.kt'; then
  echo 'M1 acquisition contracts must not become a Camera2/ImageReader owner.' >&2
  exit 1
fi

rg --fixed-strings --quiet 'Only interpretable sensor-domain evidence has a canonical sensor raster' \
  "$source_root/CanonicalRaster.kt" || {
  echo 'Canonical hashing must fail closed for processed/opaque sources.' >&2
  exit 1
}

for token in \
  'MAX_SOURCE_PLANE_BYTES' \
  'MAX_CANONICAL_RASTER_BYTES' \
  'MAX_CORPUS_ENTRIES' \
  'MAX_CORPUS_CANONICAL_BYTES'; do
  rg --fixed-strings --quiet "$token" "$source_root/AcquisitionModel.kt" || {
    echo "M1 bounded-ingest limit missing: $token" >&2
    exit 1
  }
done

for test_token in \
  'canonicalHashIgnoresRowPaddingButPreservesMeaningfulSamples' \
  'planeExtentOverflowFailsClosed' \
  'concurrentDuplicateDeliveryAdoptsOnlyOnePayload' \
  'staleDeliveryCannotConsumeNewerPermit' \
  'corpusEnforcesEntryAndByteBounds'; do
  rg --fixed-strings --quiet "$test_token" "$test_root" || {
    echo "M1 acceptance test missing: $test_token" >&2
    exit 1
  }
done

echo 'M1 immutable acquisition and source-corpus verification passed.'
