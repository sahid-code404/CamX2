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
readonly reservation_test='app/src/test/java/com/sahidcode404/camx/core/rawvideo/recording/SensorRawVideoReservationTest.kt'
readonly pairer_test='app/src/test/java/com/sahidcode404/camx/core/rawvideo/recording/RawVideoTimestampPairerTest.kt'
readonly detached_test='app/src/test/java/com/sahidcode404/camx/core/rawvideo/recording/DetachedRawSensorImageTest.kt'

for file in \
  "$owner" "$model" "$pairer" "$detached" "$assembler" "$ingest" "$spool" "$store" \
  "$graph" "$activity" "$doc" "$reservation_test" "$pairer_test" "$detached_test"; do
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
  'DEFAULT_INGEST_QUEUE_FRAMES = 2' \
  'DEFAULT_MAX_RESIDENT_BYTES = 256L * 1024L * 1024L' \
  'defaultDetachedPairingBudget(canonicalBytesPerFrame' \
  'val spoolQueueFrames: Int' \
  'val pairingPendingImageFrames: Int' \
  'val pairingPendingImageBytes: Long' \
  'val reservedSpoolQueueBytes: Long' \
  'val reservedDetachedPairingBytes: Long' \
  'spoolQueueFrames = ingestQueueFrames' \
  'reservedSpoolQueueBytes = queueBytes' \
  'reservedDetachedPairingBytes = pairingBudget.reservedDetachedBytes' \
  'sealed interface SensorRawVideoStatus' \
  'sealed interface SensorRawVideoStartOutcome' \
  'sealed interface SensorRawVideoStopOutcome'; do
  rg --fixed-strings --quiet "$token" "$model" || {
    echo "M10 bounded model contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'ArrayBlockingQueue<SensorRawVideoFrameBatch>(reservation.ingestQueueFrames)' \
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
  'internal interface RetainedByteEvidence' \
  'private val images = LinkedHashMap<Long, PendingImage>()' \
  'DetachedRawSensorImage.copyAndClose(image)' \
  'private fun detachImageReaderLease(image: I): AutoCloseable' \
  'defaultDetachedPairingBudget(retainedBytes).pendingImageBytes' \
  'fun pendingImageByteCount(): Long' \
  'pendingImageBytes = Math.subtractExact' ; do
  rg --fixed-strings --quiet "$token" "$pairer" || {
    echo "M10 timestamp-pairing lease/memory bound missing: $token" >&2
    exit 1
  }
done

for token in \
  'class DetachedRawSensorImage' \
  ': RetainedByteEvidence' \
  'source.close()' \
  'fun takeCanonicalRaster(): ByteArray' \
  'internal fun copyCanonicalRawPlane(' \
  'val sourceBase = source.position().toLong()' \
  'val availableBytes = source.remaining().toLong()' \
  'val canonical = ByteArray(canonicalBytesLong.toInt())'; do
  rg --fixed-strings --quiet "$token" "$detached" || {
    echo "M10 detached RAW evidence contract missing: $token" >&2
    exit 1
  }
done

if rg --line-number ':\s*Image(\.Plane)?\s*\(' "$detached"; then
  echo 'M10 detached evidence must not subclass framework Image or Image.Plane.' >&2
  exit 1
fi

if rg --fixed-strings --quiet 'apply { clear() }' "$detached"; then
  echo 'M10 detached RAW copy must respect the Image plane ByteBuffer position/limit window.' >&2
  exit 1
fi

for token in \
  'pair.takeDetachedRawSensorImage()' \
  'evidence.takeCanonicalRaster()' \
  'evidence.sourceRowStrideBytes' \
  'evidence.sourcePixelStrideBytes'; do
  rg --fixed-strings --quiet "$token" "$assembler" || {
    echo "M10 detached assembler contract missing: $token" >&2
    exit 1
  }
done

for token in \
  'ArrayBlockingQueue<FrameBatch>(queueFrames)' \
  'private data class FrameBatch(' \
  'queue.offer(FrameBatch(gapBefore, frame))' \
  'batch.gapBefore?.let(::appendGap)' \
  'appendFrame(batch.frame)'; do
  rg --fixed-strings --quiet "$token" "$spool" || {
    echo "M10 full-frame spool bound missing: $token" >&2
    exit 1
  }
done

if rg --fixed-strings --quiet 'queueCapacityRecords' "$spool"; then
  echo 'M10 spool queue must be bounded by full RAW frames, not a larger generic record count.' >&2
  exit 1
fi

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

for token in \
  'reservationProvesBothFrameQueuesAndDetachedPairingBeforeCapture' \
  'reservationFailsClosedWhenDetachedPendingAndInflightFramesCannotFit' \
  'reservationFailsClosedWhenFrameQueuesCannotFitBudget'; do
  rg --fixed-strings --quiet "$token" "$reservation_test" || {
    echo "M10 detached/spool admission test missing: $token" >&2
    exit 1
  }
done

for token in \
  'detachedByteOverflowClosesAllOwnedEvidenceAndFailsInsteadOfDropping' \
  'detachedFrameByteExtentCannotChangeInsidePairingEpoch'; do
  rg --fixed-strings --quiet "$token" "$pairer_test" || {
    echo "M10 detached pairer byte test missing: $token" >&2
    exit 1
  }
done

for token in \
  'canonicalCopyRespectsNonzeroBufferPositionAndLimit' \
  'canonicalCopyFailsWhenDeclaredRowsExceedBufferWindow'; do
  rg --fixed-strings --quiet "$token" "$detached_test" || {
    echo "M10 RAW buffer-window test missing: $token" >&2
    exit 1
  }
done

if rg --line-number 'acquireLatestImage\s*\(' "$owner" "$ingest" "$assembler"; then
  echo 'M10 forbids acquireLatestImage because continuous sensor evidence may not be silently dropped.' >&2
  exit 1
fi

# CaptureResult and Image evidence may cross the synchronous recording boundary, but CameraDevice,
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
rg --fixed-strings --quiet 'detached pairing memory' "$doc"
rg --fixed-strings --quiet 'spool queue' "$doc"

echo 'M10 continuous sensor RAW-video acquisition and UI integration verification passed.'
