# M4 — Bounded Burst Acquisition

Status: Software checkpoint in implementation; exact-profile physical burst acceptance remains a separate evidence gate

M4 extends the accepted CamX2 one-shot RAW transaction seam into a finite, pre-admitted sensor burst that produces one immutable `FrameSet` for one canonical lens. It does not implement HDR, night, alignment, noise calibration, fusion, super-resolution, RAW video, or another Camera2 owner.

## Implemented contract

Production burst contracts live in `core/camera/raw/`:

- `RawBurstReservation` proves bounded frame count, source extent, canonical-copy extent, metadata, safety margin, resident memory, and timeout before capture.
- `RawBurstTimestampPairer` performs exact SENSOR_TIMESTAMP pairing across callback order while binding each result to an explicit request ordinal.
- `RawBurstPairSet` is an all-or-nothing move-only ownership set; partial sets never escape.
- `ImmutableRawBurstFrame` stores padding-free public `RAW_SENSOR` rows plus source stride/pixel-stride evidence, timestamp/frame metadata, and canonical SHA-256.
- `ImmutableRawFrameSet` freezes exact contiguous membership for one `RawCaptureContext` and one reservation.
- `RawBurstCaptureOutcome` keeps capture-integrity failure separate from later scientific processing.

The typed session output contract adds `CameraRequestLifetime.BOUNDED_BURST`. RAW remains transaction-only and can never become a repeating preview output.

## Reservation truth

M4 does not infer a source-buffer bound from RAW dimensions alone. Camera2 does not advertise the eventual `Image.Plane.rowStride` before the frame arrives, so the live burst API requires a caller-supplied exact-profile `maxSourceBytesPerFrame` from prior certification evidence. The delivered source extent is checked against that bound.

The implementation safety ceiling is 16 frames and 512 MiB of canonical burst bytes, with a 1 GiB resident ceiling. These are engineering caps, not a fixed photography recipe. A later `CaptureRecipe` planner may request any admitted count inside the bounds based on scene evidence.

## Camera ownership

`CameraSessionController` remains the sole Camera2/ImageReader/session/request owner. The burst uses the current verified preview/device, advances the session generation, assigns one capture token, detaches the preview session, creates one temporary preview+RAW session, submits a finite `captureBurst`, copies accepted RAW rows away from Android `Image` ownership, destroys RAW transaction resources, and restores preview through the existing safe-baseline restoration path.

The ImageReader listener uses `acquireNextImage()` only. Arbitrary evidence dropping through `acquireLatestImage()` is forbidden.

## Callback and stale semantics

Callbacks only acquire, validate, timestamp/ordinal-pair, signal completion, and close stale evidence. Duplicate image timestamps, duplicate result timestamps, duplicate request ordinals, out-of-range ordinals, excess callbacks, malformed timestamps, capture failure, sequence abort, timeout, or cancellation fail the whole set. No trimming policy is allowed because trimming would silently change the captured evidence set.

Selection/surface/pause/shutdown races invalidate the authoritative capture identity. Cleanup may continue, but a stale transaction cannot publish its `FrameSet` or restore an obsolete preview selection.

## Canonicalization boundary

After all frames are paired, public `RAW_SENSOR` images are copied sequentially on `Dispatchers.Default`, not inside the Camera2 callback. M4 requires one RAW plane, two-byte pixel stride, dimensions matching the reservation, a row stride at least as large as the meaningful row, a source extent within the admitted per-frame bound, and matching image/result SENSOR_TIMESTAMP.

Only meaningful width × 2 bytes are copied from each row. Undefined row padding is excluded. Each source Android `Image` is then closed. This is representation-preserving canonicalization, not reconstruction.

## Tier-A review fields

**Invariant:** one `CameraSessionController` owns all Camera2 resources; one burst binds one canonical lens/profile/route/capture token and cannot silently drop frames.

**Ownership transfer:** ImageReader -> strict pairer -> complete pair set -> sequential canonical copy -> immutable `FrameSet`; every Android image has one close path.

**Stale behavior:** any authoritative generation/selection mutation cancels publication; stale cleanup has no authority to mutate current selection or camera trust.

**Failure classification:** reservation, pairing, malformed RAW layout, timeout, or capture failure remains in acquisition/capture-integrity domains only. No processing/storage failure can reclassify camera support.

**Unit tests:** reservation arithmetic, budget rejection, immutable copy, contiguous set membership, callback-order independence, duplicate rejection, incomplete cleanup, and move-once ownership.

**CI guard impact:** `verify-m4-burst.sh` is added after the M3 guard and before camera UI/native/build validation.

**Hardware acceptance step:** exact-profile real-device burst proof with strides/extents, timestamps/ordinals, memory high-water, closure counters, races, duration, and preview restoration. Until that evidence exists, M4 is not a physical certification claim.

## Explicitly deferred

M4 does not choose exposure brackets or adaptive frame counts, does not run M5 calibration/noise modeling, M6 alignment/visibility, M7 reconstruction, or M10 continuous RAW video. The next dependency-ordered scientific milestone after green M4 is M5.
