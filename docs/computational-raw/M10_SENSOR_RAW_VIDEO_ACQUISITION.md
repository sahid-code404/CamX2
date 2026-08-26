# M10 — Continuous Sensor RAW Video Acquisition

Status: software checkpoint implemented on `phase/camx2-m10-sensor-raw-video`; the exact-device `ImageReader.maxImages` lease fix is being validated on `phase/camx2-m10-image-lease-fix`.

## Scope

M10 is the first dependency-legal continuous sensor RAW-video acquisition milestone. It connects the existing M2A/M2B container and codec work to the sole production Camera2 owner and exposes the resulting transaction to the Camera UI.

The production path is:

`VIDEO shutter -> VisiblePreviewGraph -> CameraSessionController -> public RAW_SENSOR repeating capture -> exact timestamp/result pairing -> bounded canonical ingest -> PACKED_NONE CXRB spool -> stop/drain/finalize -> preview restore`.

CameraSessionController remains the sole Camera2 owner. No recording, codec, storage, UI, or graph component opens a CameraDevice or owns an independent CameraCaptureSession/ImageReader.

## Acquisition semantics

The M10 reference path uses public `RAW_SENSOR` and retains the preview surface in the same temporary recording session. It uses `acquireNextImage()` rather than `acquireLatestImage()` because silent evidence dropping is not permitted.

Each RAW image is paired to its Camera2 result through the positive `SENSOR_TIMESTAMP`. Camera2 frame-number discontinuities are represented as gaps rather than hidden. The reference implementation canonicalizes the delivered RAW raster without row padding before it crosses the acquisition boundary.

A native `ImageReader` lease is never allowed to become timestamp-skew or storage-queue state. When an image arrives before its matching capture result, M10 first copies the complete declared RAW plane into detached heap-backed evidence and closes the source `Image`; timestamp pairing then owns only the detached snapshot. Once a pair exists, canonicalization again produces detached immutable frame evidence before asynchronous spool backpressure. This makes `ImageReader.maxImages` a short Camera2 delivery bound rather than a hidden buffering mechanism and directly prevents result-callback skew from exhausting acquired-image slots. The CI M10 guard checks this lease-detachment boundary so future queue changes cannot silently reintroduce native-image retention.

The recording transaction has explicit start, recording, stopping, completed, failed, and idle states. Start is not reported as successful until the first RAW frame is verified. Stop drains the bounded pipeline, finalizes the container, releases RAW resources, and restores preview through the existing session-generation machinery.

## `ImageReader.maxImages` regression acceptance

The exact-device failure signature under test is `maxImages (4) has already been acquired, call #close before acquiring more`. This is treated as an ownership regression, not as a reason to increase `maxImages`, drop frames, or switch to `acquireLatestImage()`.

For this regression to be considered fixed, every Camera2 `RAW_SENSOR` image acquired by the continuous-video listener must either be closed immediately on rejection/failure or be copied into detached evidence and have the source `Image.close()` completed before that evidence enters the timestamp-skew map. The downstream ingest queue must likewise contain canonical detached frame batches rather than live Camera2 `Image` objects. Repeated start/stop testing on the affected handset must run without the `maxImages` exception before this branch can be promoted.

## Admission, memory, and storage

M10 reserves the canonical ingest queue before starting acquisition. The default queue is intentionally small and bounded. The Camera2 callback path may wait for a short bounded interval when that queue is temporarily full; this is explicit backpressure, not silent frame dropping. If capacity does not recover within that interval, recording fails rather than discarding sensor evidence.

Queue overflow, source-shape divergence, malformed timestamps, storage failure, and other evidence-integrity failures stop/fail the transaction instead of silently discarding sensor frames.

The Android storage factory writes a new `.cxrb` file under the app external-files Movies directory (or app-private fallback when external app storage is unavailable). It reserves free space before creating a transaction and does not request broad media-storage permission.

The current reference spool uses mandatory `PACKED_NONE`. The CXRB container remains the provisional reference container from M2A; this milestone does not promote CXRB or another codec/container to a universal shipping standard.

## Camera UI

The Camera screen now exposes a real VIDEO shutter rather than a placeholder. The shutter is enabled only when the active verified logical route can attempt public RAW video. Pressing it starts the M10 transaction. While recording, the control becomes a stop control, shows a recording timer, disables lens switching/PHOTO capture, and pressing it again drains and finalizes the RAW file.

If the active route cannot support the public RAW_SENSOR requirements, VIDEO remains visible but disabled with an explicit availability message. Runtime RAW-video failures are surfaced with their concrete pipeline reason rather than being hidden behind a generic cancellation message. A start timeout is also reported explicitly.

## Failure/lifecycle behavior

Pause, surface invalidation, lens selection changes, shutdown, Camera2 terminal failures, ingest failure, or spool failure invalidate the active recording transaction through the same lifecycle-scoped owner. Partial output is never represented as a successful completed recording. Recoverable output may remain for forensic/recovery purposes when the transaction had already begun writing; explicit admission/start failures delete their newly created empty output.

## Acceptance boundary

Sensor RAW video is not physically certified by CI. CI can prove source contracts, bounded models, deterministic container/codec behavior, compilation, API-23 compatibility, unit tests, architecture guards, and APK packaging, but it cannot prove sustained write margin, real sensor frame cadence, thermal behavior, device-specific stream combinations, long-duration stability, or power-loss recovery on a particular handset.

Physical M10 acceptance therefore still requires exact-device testing of start/stop, frame/gap accounting, file recovery, sustained duration, storage pressure, pause/lens-switch interruption, thermal behavior, and replay/decoder verification of captured sensor samples.
