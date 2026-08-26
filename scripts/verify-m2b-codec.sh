#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly prod="app/src/main/java/com/sahidcode404/camx/core/rawvideo/codec"
readonly tests="app/src/test/java/com/sahidcode404/camx/core/rawvideo/codec"
readonly implementation_doc="docs/computational-raw/M2B_CODEC_PROTOTYPE.md"
readonly benchmark_doc="docs/computational-raw/M2B_BENCHMARK_REPORT.md"
readonly adr="docs/adr/ADR-034-rice-delta-reference-codec-prototype.md"

for path in \
  "$prod/RawVideoCodecContract.kt" \
  "$prod/PackedNoneCodec.kt" \
  "$prod/RiceDeltaByteCodec.kt" \
  "$tests/RawVideoCodecTestFixtures.kt" \
  "$tests/PackedNoneCodecTest.kt" \
  "$tests/RiceDeltaByteCodecTest.kt" \
  "$tests/RawVideoCodecFuzzTest.kt" \
  "$implementation_doc" \
  "$benchmark_doc" \
  "$adr"; do
  test -f "$path" || { echo "M2B artifact missing: $path" >&2; exit 1; }
done

for requirement in \
  'family = "PACKED_NONE"' \
  'family = "RICE_DELTA_BYTE"' \
  'BYTE_DELTA_MOD_256' \
  'MAX_ENCODED_FRAME_BYTES = 603_979_776L' \
  'independentFrameDecode' \
  'decodedRasterSha256' \
  'representationDescriptorSha256' \
  'encodedCrc32' \
  'EncodedFrameLease' \
  'DecodedFrameLease' \
  'fun reservationFor(decodedBytes: Long): CodecReservation'; do
  if ! rg --fixed-strings --quiet "$requirement" "$prod"; then
    echo "M2B contract requirement missing: $requirement" >&2
    exit 1
  fi
done

if rg --line-number \
  'android\.hardware\.camera2|android\.media\.Image|CameraSessionController|CameraManager|CameraDevice|ImageReader|acquireLatestImage|core\.rawvideo\.container|java\.nio\.file' \
  --glob '*.kt' "$prod"; then
  echo 'M2B codec package crossed a forbidden camera/container/filesystem boundary.' >&2
  exit 1
fi

for test_requirement in \
  'packedNoneRoundTripIsBitExactAndDescriptorBound' \
  'structuredSensorBytesRoundTripBitExactlyAndCompress' \
  'eachFrameDecodesIndependentlyWithoutPriorFrameState' \
  'maximumM1FrameReservationUsesLongArithmeticWithoutAllocation' \
  'deterministicCorpusRoundTripsAcrossPackedAndRiceCodecs' \
  'mutatedRicePayloadNeverReturnsWrongRaster'; do
  if ! rg --fixed-strings --quiet "$test_requirement" "$tests"; then
    echo "M2B acceptance test missing: $test_requirement" >&2
    exit 1
  fi
done

rg --fixed-strings --quiet 'Status: Provisional' "$adr"
rg --fixed-strings --quiet '`PACKED_NONE` remains frozen and mandatory.' "$adr"
rg --fixed-strings --quiet 'physical acceptance and shipping selection pending' "$implementation_doc"
rg --fixed-strings --quiet 'physical Pareto benchmark pending' "$benchmark_doc"

echo 'M2B provisional RAW-video codec verification passed.'
