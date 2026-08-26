#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
failures=0

if rg --line-number '\bACameraManager_openCamera\b|\bACameraDevice\b|\bACameraCaptureSession\b' \
  --glob '*.c' --glob '*.cc' --glob '*.cpp' --glob '*.cxx' \
  --glob '*.h' --glob '*.hpp' --glob '*.hxx' native/core; then
  echo 'Native boundary violation: Camera NDK control plane is not authorized.' >&2
  failures=$((failures + 1))
fi
if rg --line-number '\b(?:dlopen|android_dlopen_ext)\s*\([^\n]*(?:vendor|camera\.so)' \
  --glob '*.c' --glob '*.cc' --glob '*.cpp' --glob '*.cxx' native/core; then
  echo '::error title=Native boundary violation::Private vendor camera library loading found.'
  echo 'Native boundary violation: private vendor camera library loading.' >&2
  failures=$((failures + 1))
fi
if rg --line-number '\bvoid\s*\*\s*(?:owner|owned|image|buffer|handle)\b' \
  --glob '*.c' --glob '*.cc' --glob '*.cpp' --glob '*.cxx' \
  --glob '*.h' --glob '*.hpp' --glob '*.hxx' native/core; then
  echo '::error title=Native boundary violation::Owning bare void pointer found.'
  echo 'Native boundary violation: owning bare void pointer.' >&2
  failures=$((failures + 1))
fi

for requirement in \
  'cxx_std_20' '-Wall' '-Wextra' '-Werror' \
  'UniqueNdkOwner' 'BoundedTimestampIndex' 'NativeBufferPool' 'NativeTraceBuffer' 'ResourceCounters'; do
  if ! rg --fixed-strings --quiet -- "$requirement" native/core; then
    echo "Native boundary requirement missing: $requirement" >&2
    failures=$((failures + 1))
  fi
done

((failures == 0)) || exit 1
echo 'Native boundary verification passed.'
