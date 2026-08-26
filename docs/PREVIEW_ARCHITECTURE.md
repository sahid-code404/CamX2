# Preview Architecture

`SurfaceViewPreviewSurface` is the initial backend because it can feed a Camera2 PRIVATE surface
without routing pixels through Compose or an application ImageReader. The view is remembered for the
screen lifetime. Surface lifecycle produces explicit leases whose identities and session generations
must match before attach/detach; an old callback cannot unbind a newer surface.

CAMX-104 keeps preview stream choice pure. `AUTO` prefers an advertised `CAMERA2_PRIVATE` candidate
when cadence evidence is equivalent because it is the display-oriented path. Explicit PRIVATE or YUV
requests filter strictly to that advertised stream type. Selecting a YUV capability here does not
create a YUV owner, ImageReader, processing pipeline, or UI surface integration; those remain separate
resource/integration concerns.

Responsive size selection is relative to the actual view and rotated stream geometry, never a fixed
resolution or aspect ratio. Normal preview targets roughly one effective source pixel per view pixel
after center crop. High-resolution preference targets two-times linear oversampling (four-times
effective source pixels), then chooses the closest cadence-compatible advertised candidate instead of
blindly selecting the largest stream. A bounded candidate limit fails closed before sorting.

`PreviewGeometryCalculator` derives normalized rotation from sensor orientation, display rotation, and
facing, swaps stream axes at 90/270 degrees, then applies one uniform center-crop scale. Rendered width
and height cover the view, translation centers the crop, and horizontal mirroring is enabled only for
an explicitly mirrored FRONT preview. No previous-lens transform is retained.

## FPS resolution

`PreviewFpsResolver` is the single pure FPS policy used by CAMX-104 and later request construction. An
override-disabled request always resolves to no explicit range with `OVERRIDE_DISABLED`; requested
minimum/maximum values are not treated as camera truth in that state. With override enabled, nonpositive
or inverted requests fail closed as `INVALID_REQUEST`, and an empty capability set produces
`NO_REPORTED_RANGES`. Resolver input is bounded to the existing 64-range active-profile/cache contract
before deduplication or sorting.

Advertised ranges are deduplicated and deterministically ordered. An exact requested range wins only
when it remains compatible with known selected-stream cadence. Otherwise nearest selection minimizes
`abs(advertisedMin-requestedMin) + abs(advertisedMax-requestedMax)` using `Long` arithmetic; ties prefer
the closer maximum endpoint, then the higher advertised maximum, then the higher advertised minimum.
The returned range is therefore always either absent or an exact advertised `CameraFpsCapability`.

A positive `minimumFrameDurationNs` supplies cadence evidence through integer
`floor(1_000_000_000 / duration)` arithmetic. Ranges whose advertised maximum exceeds that cadence are
excluded. `null` and `0` remain unknown evidence and do not fabricate either infinite or zero FPS.
`STREAM_CADENCE_LIMIT` is used when cadence removes every range or actually changes the nearest winner;
filtering an unrelated range does not relabel an otherwise identical nearest-range fallback.

## Actual preview-frame metrics

`PreviewFrameMetrics` owns exactly one fixed `LongArray` interval ring, constructed once with capacity
2 through 4096 (default 120). The frame append path stores its previous timestamp in a primitive `Long`
with zero as the internal uninitialized sentinel, so accepted frames do not box a nullable timestamp.
`recordSensorTimestamp()` rejects zero, negative, duplicate, and backwards timestamps without moving
the last valid baseline. A later increasing timestamp therefore measures from the last valid sample.
Once full, the ring overwrites its oldest interval deterministically and `sampleCount` never exceeds
capacity.

The append path is synchronized, constant-time, fixed-memory, and allocation-free after construction.
It performs no string/JSON/log formatting, sorting, collection growth, coroutine/thread dispatch,
StateFlow/Compose mutation, or settings read. Diagnostics consume `snapshot()` at low frequency instead
of publishing frame-by-frame UI state.

`snapshot()` may allocate and sort a bounded copy of the currently retained intervals. Its
`movingAverageFps` is `1_000_000_000 / arithmeticMean(retainedIntervals)` and therefore describes only
the current ring window, never lifetime frames that have already been overwritten. p50 and p95 use
integer nearest-rank semantics: rank `ceil(sampleCount * percentile)`, clamped to the retained sample
range. Empty metrics return zero samples and null cadence/percentile values. The immutable request and
resolved advertised range supplied at metrics construction are retained as the measurement identity;
later settings values cannot retroactively change an existing measurement window.

A resolved `PreviewConfiguration` uses only an advertised concrete stream type and size. Its `pv1`
signature is deterministic and contains requested stream policy, resolved type/size, high-resolution
state, FPS request, resolved FPS range, and FPS fallback reason. No timestamp, object identity, locale,
camera-ID meaning, or random value participates.

CI/JVM coverage proves resolver, bounded metrics, policy, and build correctness only. Physical sustained
preview cadence, first-request metadata, crop, mirror, and lens behavior remain hardware-acceptance
evidence and are not inferred from host tests.
