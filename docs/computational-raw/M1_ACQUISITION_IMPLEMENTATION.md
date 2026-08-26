# M1 — Immutable Acquisition Model and Source Corpus

Status: Implemented software checkpoint; physical one-shot hardware acceptance remains a separate evidence gate

## Scope

M1 extends the accepted CAMX-108 one-shot RAW contract without adding a camera owner. It introduces representation-typed acquisition descriptors, historical capture identity, deterministic canonical-raster hashing, a generation/permit-bound exact-once handoff seam, and bounded immutable source-corpus snapshots. It intentionally does not add reconstruction, burst/video expansion, private RAW decoding, DNG changes, GPU work, or processing on a Camera2 callback.

The Android install identity remains `com.sahidcode404.camx2`; the migrated source namespace remains `com.sahidcode404.camx`.

## Tier-A review record

**Invariant.** `CameraSessionController` remains the sole Camera2/ImageReader authority. M1 code under `core/camera/acquisition` imports neither Camera2 nor Android `Image`; it consumes already matched evidence behind immutable contracts. Opaque/private transports remain explicitly opaque and cannot receive a canonical sensor-raster hash.

**Ownership transfer.** `AcquisitionHandoffGate` issues one opaque permit for one immutable `AcquisitionPermitIdentity`. `transfer` accepts the exact current permit and matching historical identity once, producing one move-only `AcquisitionLease`. Rejected payloads are closed after the synchronized admission decision. `AcquisitionLease.take()` moves ownership out exactly once; otherwise `close()` releases it exactly once.

**Stale behavior.** A replaced, invalidated, foreign, duplicate, or already-consumed permit is stale. Stale delivery closes only its delivered payload and cannot consume a newer permit. Identity mismatch consumes the matching pending permit, closes the mismatched payload, and fails closed. No live UI, topology, or current-selection query can reinterpret a transferred capture.

**Failure classification.** Descriptor shape/stride/active-area/precision/extent errors are rejected before hashing. Arithmetic overflow is converted to `IllegalArgumentException`. Processed or opaque source classes are rejected by canonical sensor hashing. Rejected-payload close failure is surfaced in `AcquisitionTransfer.Rejected.closeFailure` without re-opening ownership. Corpus duplicate, entry-bound, byte-bound, and byte-total overflow failures are explicit admission failures and do not mutate camera trust.

**Unit tests.** Tests cover defensive collection/byte copying, representation truth, malformed active area, stride/length/overflow rejection, deterministic descriptor hashing, padding-independent canonical raster hashing, processed-source rejection, accepted/moved/closed lease behavior, stale and mismatched permits, concurrent duplicate-delivery race admission, close failure, deterministic manifest IDs, duplicate corpus entries, immutable snapshots, stratification, and corpus bounds.

**CI guard impact.** `scripts/verify-m1-acquisition.sh` is added to the normal CI sequence after the frozen Revision-2 architecture guard. It verifies required M1 contracts/tests, forbids Camera2/ImageReader ownership imports in the acquisition boundary, requires bounded-ingest constants, and checks acceptance-test inventory. Existing architecture, camera-owner, API-23, native, lint, unit-test, signed-APK, and OTA gates remain unchanged.

**Hardware acceptance step.** The software checkpoint does not claim physical support. M1 hardware acceptance requires a real-device one-shot run on the accepted RAW path that records exact lens/profile/route, public source representation, plane/stride descriptor, image/result timestamps and timebase evidence, canonical raster hash, descriptor hash, permit outcome, and exact close/release counters. Evidence must demonstrate that the existing preview resumes and that no second camera owner appears. Exact-profile physical support remains unclaimed until that evidence exists.

## Implemented boundaries

- `AcquisitionModel.kt` — truth-preserving representation hierarchy, bounded plane descriptors, sensor-mode/CFA/calibration/provenance/timebase and historical capture identity.
- `RawContextBridge.kt` — pure conversion from accepted `RawCaptureContext` into M1 permit/capture identity with no live-state lookup.
- `CanonicalRaster.kt` — canonical meaningful-row hashing that excludes storage padding, plus deterministic descriptor SHA-256.
- `AcquisitionHandoff.kt` — exact-current-permit admission and move-only payload lease.
- `SourceCorpus.kt` — deterministic source manifest IDs, stratification, bounded ingest, immutable sorted snapshots.

## Explicitly deferred

M2A/M2B container/codec work, M3 reference reconstruction, burst scheduling, computational-negative production, DNG export changes, Vulkan/SIMD/AI providers, and physical-profile certification are not part of M1.
