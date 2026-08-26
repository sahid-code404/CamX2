#!/usr/bin/env bash
set -euo pipefail

readonly root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

readonly owner='app/src/main/java/com/sahidcode404/camx/core/camera/session/CameraSessionController.kt'
readonly model='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/SensorRawVideoModel.kt'
readonly pairer='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/RawVideoTimestampPairer.kt'
readonly detached='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/DetachedRawSensorImage.kt'
readonly assembler='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/SensorRawVideoFrameAssembler.kt'
readonly ingest='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/AndroidSensorRawVideoIngest.kt'
readonly spool='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/CxrbSensorRawVideoSpool.kt'
readonly store='app/src/main/java/com/sahidcode404/camx/core/rawvideo/recording/AndroidSensorRawVideoStore.kt'
readonly graph='app/src/main/java/com/sahidcode404/camx/core/camera/bootstrap/VisiblePreviewGraph.kt'
readonly activity='app/src/main/java/com/sahidcode404/camx/MainActivity.kt'
readonly doc='docs/computational-raw/M10_SENSOR_RAW_VIDEO_ACQUISITION.md'

for file in "$owner" "$model" "$pairer" "$detached" "$assembler" "$ingest" "$spool" "$store" "$graph" "$activity" "$doc"; do
  test -s "$file" || { echo "M10 artifact missing: $file" >&2; exit 1; }
done

for token in \
  'internal suspend fun startRawVideo(' \
  'internal suspend fun stopRawVideo()' \
  'CameraSessionOutputPlan.continuousRawVideo(' \
  'SensorRawVideoReservation.forRawSensor(' \
  'SensorRawVideoStatus.Recording(' \
  'restoreAfterRawVideo(' \
  'source.acquireNextImage()' \
  'CameraDevice.TEMPLATE_PREVIEW' \
  'setRepeatingRequest(' ; do
  rg --fixed-strings --quiet "$token" "$owner" || {
    echo "M10 controller contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'HandlerThread("camx-raw-video-images")' \
  'imageCallbackHandler' \
  'imageCallbackThread'; do
  rg --fixed-strings --quiet "$token" "$owner" || {
    echo "M10 dedicated image callback handler contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'DEFAULT_INGEST_QUEUE_FRAMES = 2' \
  'DEFAULT_MAX_RESIDENT_BYTES = 256L * 1024L * 1024L' \
  'sealed interface SensorRawVideoStatus' \
  'sealed interface SensorRawVideoStartOutcome' \
  'sealed interface SensorRawVideoStopOutcome'; do
  rg --fixed-strings --quiet "$token" "$model" || {
    echo "M10 bounded model contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'ArrayBlockingQueue<DetachedRawVideoPair>(reservation.ingestQueueFrames)' \
  'DetachedRawVideoPair.from(pair)' \
  'assembler.assemble(' \
  'queue.offer(' ; do
  rg --fixed-strings --quiet "$token" "$ingest" || {
    echo "M10 detached ingest ownership contract missing: $token" >&2
    exit 1
  }
done

if rg --fixed-strings --quiet 'ArrayBlockingQueue<PairedRawVideoSample<Image, CaptureResult>>' "$ingest"; then
  echo 'M10 ingest queue must not retain live Camera2 Image ownership.' >&2
  exit 1
fi

for token in \
  'DetachedRawSensorImage.copyAndClose(image)' \
  'image is DetachedRawSensorImage' \
  'class DetachedRawVideoPair' \
  'DetachedRawVideoPair.from(pair)'; do
  rg --fixed-strings --quiet "$token" "$pairer" || {
    echo "M10 timestamp-pairing lease detachment missing: $token" >&2
    exit 1
  }
done

for token in \
  'class DetachedRawSensorImage' \
  'sourceRequiredBytes' \
  'sourceBuffer.limit(sourceRequiredBytes.toInt())' \
  'source.close()' \
  'ByteBuffer.wrap(bytes).asReadOnlyBuffer()'; do
  rg --fixed-strings --quiet "$token" "$detached" || {
    echo "M10 detached RAW image contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'AndroidSensorRawVideoStore(appContext)' \
  'controller.startRawVideo(displayRotation, rawVideoStore)' \
  'controller.stopRawVideo()'; do
  rg --fixed-strings --quiet "$token" "$graph" || {
    echo "M10 graph integration missing: $token" >&2
    exit 1
  }
done

for token in \
  'visiblePreviewGraph.startRawVideo(currentDisplayRotation())' \
  'visiblePreviewGraph.stopRawVideo()' \
  'SensorRawVideoStatus.Recording'; do
  rg --fixed-strings --quiet "$token" "$activity" || {
    echo "M10 UI integration missing: $token" >&2
    exit 1
  }
done

if rg --line-number 'acquireLatestImage\s*\(' "$owner" "$ingest" "$assembler"; then
  echo 'M10 forbids acquireLatestImage because continuous sensor evidence may not be silently dropped.' >&2
  exit 1
fi

# CaptureResult is immutable evidence metadata and is allowed outside the owner. CameraDevice,
# CameraCaptureSession, CameraManager, and ImageReader remain prohibited in recording modules.
if rg --line-number \
  '^import android\.hardware\.camera2\.(CameraDevice|CameraCaptureSession|CameraManager)|^import android\.media\.ImageReader' \
  "$model" "$pairer" "$detached" "$assembler" "$ingest" "$spool" "$store"; then
  echo 'M10 recording modules must not become independent Camera2/ImageReader owners.' >&2
  exit 1
fi

rg --fixed-strings --quiet 'PACKED_NONE' "$spool"
rg --fixed-strings --quiet 'Sensor RAW video is not physically certified by CI.' "$doc"
rg --fixed-strings --quiet 'CameraSessionController remains the sole Camera2 owner.' "$doc"

echo 'M10 continuous sensor RAW-video acquisition and UI integration verification passed.'
