#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly source_root='app/src/main/java/com/sahidcode404/camx/core/rawvideo/container'
readonly test_root='app/src/test/java/com/sahidcode404/camx/core/rawvideo/container'
readonly status_doc='docs/computational-raw/M2A_CONTAINER_PROTOTYPE.md'
readonly benchmark_doc='docs/computational-raw/M2A_BENCHMARK_REPORT.md'
readonly adr='docs/adr/ADR-033-cxrb-reference-container-prototype.md'

for file in \
  "$source_root/RawVideoContainerContract.kt" \
  "$source_root/CxrbBinary.kt" \
  "$source_root/CxrbReferenceWriter.kt" \
  "$source_root/CxrbRecovery.kt" \
  "$test_root/CxrbReferenceWriterTest.kt" \
  "$test_root/CxrbRecoveryFuzzTest.kt" \
  "$status_doc" "$benchmark_doc" "$adr"; do
  test -s "$file" || { echo "M2A container artifact missing: $file" >&2; exit 1; }
done

for token in \
  'value class FrameOrdinal(val value: ULong)' \
  'class CxrbReferenceWriter' \
  'object CxrbRecovery' \
  'FileDescriptor.sync' \
  'PACKED_NONE' \
  'FLAG_DISCONTINUITY' \
  'CHECKPOINT_MAGIC' \
  'MAX_RECOVERY_SCAN_BYTES' \
  'supports64BitOffsets'; do
  rg --fixed-strings --quiet "$token" "$source_root" "$adr" || {
    echo "M2A required container contract missing: $token" >&2
    exit 1
  }
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|acquireLatestImage' \
  "$source_root" --glob '*.kt'; then
  echo 'M2A container candidate must not own or import camera resources.' >&2
  exit 1
fi

if rg --line-number 'java\.nio\.file\.' "$source_root" --glob '*.kt'; then
  echo 'M2A required API-23 path must not depend on java.nio.file.' >&2
  exit 1
fi

for test_token in \
  'checkpointedSegmentsRecoverExactlyWithExplicitGap' \
  'truncatedTailRecoversToPreviousDurableCheckpoint' \
  'corruptionInsideLastSegmentHasOneSegmentCorruptionRadius' \
  'uint64MaximumOrdinalCanBeTheFinalRecord' \
  'fileHeaderRoundTripsStorageLimitAboveTwoGiBWithoutAllocation' \
  'deterministicMutationAndTruncationFuzzNeverEscapesRecoveryBounds'; do
  rg --fixed-strings --quiet "$test_token" "$test_root" || {
    echo "M2A acceptance test missing: $test_token" >&2
    exit 1
  }
done

rg --fixed-strings --quiet 'Status: Provisional' "$adr" || {
  echo 'M2A candidate ADR must remain provisional until physical evidence exists.' >&2
  exit 1
}
rg --fixed-strings --quiet 'CXRB is **not selected for shipping**' "$status_doc" || {
  echo 'M2A software checkpoint must not claim final container selection.' >&2
  exit 1
}
rg --fixed-strings --quiet 'physical PENDING' "$benchmark_doc" || {
  echo 'M2A benchmark report must preserve the physical-evidence gate.' >&2
  exit 1
}

echo 'M2A provisional RAW-video container reference verification passed.'
