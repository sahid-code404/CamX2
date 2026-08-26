#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly owner='app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraSessionController.kt'
readonly writer='app/src/main/java/com/sahidcode404/camx/core/camera/raw/AndroidDngWriter.kt'
readonly graph='app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewGraph.kt'
readonly activity='app/src/main/java/com/sahidcode404/camx/MainActivity.kt'
readonly screen='app/src/main/java/com/sahidcode404/camx/feature/camera/CameraScreen.kt'

for file in "$owner" "$writer" "$graph" "$activity" "$screen"; do
  test -s "$file" || { echo "Camera capture UI artifact missing: $file" >&2; exit 1; }
done

for token in \
  'internal suspend fun captureRawDng(' \
  'CameraSessionOutputPlan.temporaryRaw(' \
  'ImageReader.newInstance(' \
  'ImageFormat.RAW_SENSOR' \
  'CameraDevice.TEMPLATE_STILL_CAPTURE' \
  'source.acquireNextImage()' \
  'RawTimestampPairer<Image, CaptureResult>' \
  'CameraEngineState.ConfiguringRaw' \
  'CameraEngineState.WritingDng' \
  'CameraEngineState.RestoringPreview'; do
  rg --fixed-strings --quiet "$token" "$owner" || {
    echo "Real RAW capture contract missing: $token" >&2
    exit 1
  }
done

if rg --line-number 'acquireLatestImage\s*\(' "$owner" "$writer"; then
  echo 'RAW capture must never use acquireLatestImage because it silently drops evidence.' >&2
  exit 1
fi

if rg --line-number '^import android\.hardware\.camera2\.(CameraDevice|CameraCaptureSession)' "$writer"; then
  echo 'DNG writer must not become a CameraDevice/session owner.' >&2
  exit 1
fi

for token in \
  'DngCreator(characteristics, result)' \
  'MediaStoreTransaction(' \
  'DngOrientation.tiffOrientation('; do
  rg --fixed-strings --quiet "$token" "$writer" || {
    echo "Transactional DNG writer contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'suspend fun capturePhoto(displayRotation: DisplayRotation)' \
  'controller.captureRawDng(displayRotation, dngWriter)'; do
  rg --fixed-strings --quiet "$token" "$graph" || {
    echo "Visible preview capture bridge missing: $token" >&2
    exit 1
  }
done

for token in \
  'visiblePreviewGraph.capturePhoto(currentDisplayRotation())' \
  'photoCaptureEnabled = photoCaptureAvailable' \
  'videoCaptureEnabled = false'; do
  rg --fixed-strings --quiet "$token" "$activity" || {
    echo "MainActivity real capture wiring missing: $token" >&2
    exit 1
  }
done

for token in \
  'CameraCaptureMode.PHOTO' \
  'CameraCaptureMode.VIDEO' \
  'CameraCaptureMode.PHOTO -> onCapturePhoto()' \
  'CameraCaptureMode.VIDEO -> onToggleVideoRecording()' \
  'raw_video_m10_unavailable'; do
  rg --fixed-strings --quiet "$token" "$screen" || {
    echo "Camera mode/shutter UI contract missing: $token" >&2
    exit 1
  }
done

if rg --multiline --pcre2 --quiet 'enabled\s*=\s*false,[\s\S]{0,250}onClick\s*=\s*\{\s*\}' "$screen"; then
  echo 'Dead shutter placeholder returned to CameraScreen.' >&2
  exit 1
fi

echo 'Real photo shutter and truthful RAW-video UI verification passed.'
