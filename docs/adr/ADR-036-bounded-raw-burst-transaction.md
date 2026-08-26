# ADR-036 — Bounded RAW burst transaction

Status: Accepted for M4 software implementation; exact-profile physical acceptance pending

## Decision

M4 extends the existing `CameraSessionController` RAW transaction seam rather than introducing another camera engine. A bounded burst is one capture token, one canonical lens, one route/profile epoch, one temporary RAW session, one finite reservation, and one immutable `FrameSet` result.

The request count is supplied by a future capture planner and must be admitted before Camera2 capture. `M4BurstLimits.MAX_FRAMES` is an implementation safety ceiling, not a fixed scientific recipe. M4 therefore does not choose a magic frame count for day, night, HDR, or super-resolution behavior.

The frozen one-shot DNG path remains independently usable and rollback-safe.

## Invariant

`CameraSessionController` remains the sole `CameraDevice`, `CameraCaptureSession`, `ImageReader`, and Camera2 request owner. Burst callbacks perform only bounded timestamp/ordinal pairing and transfer. They never execute reconstruction, calibration, alignment, fusion, rendering, or storage policy.

A burst cannot mix canonical lens identity, profile, route, selection generation, session generation, capture token, source representation, or RAW dimensions.

## Admission and memory

`RawBurstReservation` proves a finite upper bound before capture for:

- requested frame count;
- canonical RAW bytes per frame;
- a caller-supplied exact-profile maximum delivered source extent per frame;
- all simultaneously retained source frames;
- immutable canonical copies;
- bounded metadata;
- a fixed safety margin;
- total resident bytes;
- total transaction timeout.

Camera2 does not advertise `Image.Plane.rowStride` before delivery. M4 therefore refuses to pretend that dimensions alone prove the source-buffer extent. The caller must supply a previously certified per-profile source extent bound. Every delivered frame is checked against that bound before it can enter the immutable set. Physical certification remains pending until real-device evidence establishes that bound for an exact profile.

## Ownership transfer

`RawBurstTimestampPairer` owns each acquired `Image` until a timestamp-matched result and request ordinal exist. Duplicate timestamps, duplicate ordinals, excess callbacks, malformed timestamps, timeout, cancellation, stale generation, or capture failure close every still-owned image and return no partial set.

After complete pairing, the controller canonicalizes public `RAW_SENSOR` rows on a non-camera compute dispatcher. Row padding is excluded; the copied byte arrays become immutable burst evidence. Each Android `Image` is closed exactly once after copying. `ImageReader` and the temporary RAW session are destroyed before preview restoration completes.

## Stale behavior

A burst is accepted only while the exact capture token, selection/session identity, preview intent, device, and temporary RAW session remain current. Selection changes, pause, shutdown, or another authoritative mutation invalidate the transaction. Local pair/set cleanup may continue, but stale work cannot publish a `FrameSet`, restore an old selection, or mutate camera trust.

## Failure classification

Reservation failure and malformed burst evidence are capture-integrity failures. Temporary-session rejection remains a RAW-session failure. Timeout remains a pairing failure. Storage, graph, codec, reconstruction, and calibration failures are not part of M4 and cannot mark a camera/profile unsupported.

No incomplete frame set is a valid fallback. Rollback is the already-working one-shot sensor DNG path.

## Unit tests

M4 requires deterministic tests for reservation arithmetic, insufficient-budget rejection, defensive immutable copying, exact contiguous frame membership, callback-order-independent pairing, duplicate timestamp/ordinal rejection, incomplete-set cleanup, and move-once image ownership. Existing camera generation/state/resource suites remain mandatory.

## CI guard impact

`scripts/verify-m4-burst.sh` requires the reservation, strict pairer, bounded-burst output lifetime, `captureBurst`, `acquireNextImage`, immutable frame set, API-23-safe implementation, M4 documentation, and absence of a second camera owner. It explicitly rejects `acquireLatestImage` in the M4 transaction.

## Hardware acceptance

M4 is not physically certified by CI. Exact-profile acceptance requires a real device run recording frame count, raw size, every delivered row/pixel stride and source extent, request/result/image timestamps and ordinals, reservation high-water, all close/release counters, capture duration, preview restoration, generation-race tests, and proof that only `CameraSessionController` owned Camera2. Each exact profile must be proven independently.
