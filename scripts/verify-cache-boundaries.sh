#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly cache_root="app/src/main/java/com/sahidcode404/camx/core/camera/cache"
failures=0

reject() {
  local label="$1"
  local pattern="$2"
  local matches
  if matches="$(rg --line-number "$pattern" --glob '*.kt' "$cache_root")"; then
    echo "::error title=Cache boundary violation::$label"
    echo "Cache boundary violation: $label" >&2
    echo "$matches" >&2
    failures=$((failures + 1))
  else
    local status=$?
    if ((status != 1)); then
      echo "Cache boundary scanner failed: $label (rg exit $status)" >&2
      failures=$((failures + 1))
    fi
  fi
}

reject 'Camera2 dependency' '^import android\.hardware\.camera2\.|\bCameraManager\b|\bCameraDevice\b|\bCameraCaptureSession\b'
reject 'camera-owner or feature dependency' \
  '^import com\.sahidcode404\.camx\.(?:core\.camera\.(?:session|runtime|discovery|raw)|feature)\.'
reject 'native/JNI dependency' \
  '^import com\.sahidcode404\.camx\.core\.camera\.diagnostics\.Native|\bSystem\.loadLibrary\s*\('
reject 'update or OTA dependency' '^import com\.sahidcode404\.camx\.core\.update\.'
reject 'Java object serialization' '\bObjectInputStream\b|\bObjectOutputStream\b|\bSerializable\b'
reject 'Parcelable persistence' '\bParcelable\b|@Parcelize\b'
reject 'reflection or JSON serializer' \
  '^import (?:kotlinx\.serialization|com\.google\.gson|com\.squareup\.moshi|org\.json)\.'

for requirement in \
  'HotStartCacheCodec' \
  'TopologyCacheCodec' \
  'DeepDiscoveryKnowledgeCodec' \
  'CRC32' \
  'HOT_PAYLOAD_BYTES = 32 * 1024' \
  'DEEP_PAYLOAD_BYTES = 64 * 1024' \
  'TOPOLOGY_PAYLOAD_BYTES = 1024 * 1024' \
  'DEEP_CANDIDATES = 128' \
  'output.fd.sync()' \
  'Os.rename('; do
  if ! rg --fixed-strings --quiet "$requirement" "$cache_root"; then
    echo "Cache persistence requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Cache boundary verification passed.'
