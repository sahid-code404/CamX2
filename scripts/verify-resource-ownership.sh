#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
readonly ownership="docs/RESOURCE_OWNERSHIP.md"

for resource in CameraDevice CameraCaptureSession Surface ImageReader 'RAW `Image`' AImage \
  AHardwareBuffer 'MediaStore row' 'callback thread'; do
  rg --fixed-strings --quiet "$resource" "$ownership" || {
    echo "Resource ownership entry missing: $resource" >&2
    exit 1
  }
done

if rg --line-number '\bobject\s+(?:RawCaptureRegistry|RawSessionMode)\b' app/src/main/java; then
  echo 'Resource ownership violation: process-global RAW/session registry.' >&2
  exit 1
fi

for contract in 'class CameraSessionOutputPlan private constructor' \
  'RAW output is transaction-only' 'fun temporaryRaw('; do
  rg --fixed-strings --quiet "$contract" \
    app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraOutputPlan.kt || {
    echo "Typed output ownership contract missing: $contract" >&2
    exit 1
  }
done

echo 'Resource ownership verification passed.'
