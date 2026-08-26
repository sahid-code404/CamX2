# M7 — Computational Photo Reconstruction

Status: Software scalar/reference checkpoint; exact-profile physical quality acceptance remains a separate evidence gate

M7 consumes immutable M5 calibrated measurement frames and exact-bound M6 alignment/visibility/occlusion evidence to produce a truthful `FusedCfaRadiance` computational negative on the real 1× reference sensor grid. This checkpoint does not render a photograph, write DNG, perform super-resolution, demosaic/remosaic, claim global-shutter geometry, or introduce AI.

## Implemented reference product

Production code lives in `core/imaging/reconstruction/`.

- `ReconstructionReservation` proves bounded resident input, active-grid output, row working memory, and safety margin before the scalar reference runs.
- `AlignmentEvidenceSet.measurementBindingSha256` binds M6 evidence to the exact M5 calibration profile, capture identity, frame metadata, and canonical source hashes. M7 rejects foreign alignment evidence even when dimensions match.
- `ReferenceReconstructionEngine` performs deterministic inverse-variance fusion only from M6-visible, uncensored, inlier measurements.
- The initial M7 reference requires identical exposure time and sensitivity identity for contributing frames. Exposure/gain-changing HDR fusion is intentionally deferred to later evidence-constrained recipes rather than pretending ISO metadata is a complete radiometric gain calibration.
- Frame-level alignment uncertainty and rolling-shutter disagreement can narrow the accepted reconstruction subset. Failure falls back full set -> smaller subset -> reference-only.
- Per-pixel occlusion, censoring, visibility loss, or excessive residual removes that measurement rather than inventing replacement evidence.
- A censored reference site is preserved as a censored boundary value with zero valid contributing samples; another frame is not used to fabricate an HDR estimate at M7.
- The output remains `GraphRepresentation.FUSED_CFA_RADIANCE`, `PhotometricDomain.LINEAR_SENSOR_RADIANCE`, 1× scale, and the original CFA pattern/site identity.
- Radiometric variance, effective sample support, censoring, reference-only fraction, alignment uncertainty, and rejected support classes remain separate uncertainty fields.
- Provenance retains every source canonical hash, included ordinals, calibration-profile digest, exact measurement binding, M6 evidence digest, algorithm ID/version, graph digest, scalar backend, deterministic class, build commit, output digest, manifest digest, and `learnedPriorChangedPixels=false`.

## Reference algorithm truth boundary

The scalar reference estimates the actual CFA color already present at each reference-grid site. It does not estimate missing colors. It therefore remains legal `FusedCfaRadiance` under the frozen contract: one physical sensor, one CFA/mode/color basis, real 1× reference grid, and no joint demosaic, super-resolution, remosaic, or virtual geometry.

The inverse-variance estimator uses M5 noise variance only for radiometric weighting. M6 alignment uncertainty is kept as a separate uncertainty dimension rather than being silently converted into DN variance without a calibrated spatial-gradient observation model.

The current reference accepts multi-frame fusion only when non-reference frames have the same non-null `exposureTimeNs` and `sensitivityIso` as the reference. A mismatch is a typed frame exclusion, not hidden exposure normalization.

## Failure and fallback

Frame-level exclusion reasons are explicit:

- outside the M6 reconstruction subset;
- exposure/sensitivity identity mismatch;
- alignment translation uncertainty above the M7 request;
- rolling-shutter disagreement above the M7 request.

Pixel-level measurements are excluded independently when invisible, censored, occluded/non-inlier, or above the M7 residual gate. The uncensored reference measurement is always the deterministic valid fallback. If the reference site itself is censored, M7 preserves the boundary and marks the output measurement invalid instead of synthesizing detail.

## Determinism and provenance

The reference graph semantics are frozen by `M7ReferenceSemantics.GRAPH_CANONICAL` and its SHA-256. The implementation uses a fixed scalar traversal order and stores the computational product in deterministic primitive arrays. The output digest includes grid geometry, CFA identity, radiance, variance, effective support, contributor counts, and censoring/reference-only flags. The manifest digest additionally binds sources, exact M5/M6 evidence, algorithm/graph/backend/determinism identity, build commit, fallback, and output digest.

No learned prior changes pixels in this checkpoint.

## Validation

Unit tests cover:

- symmetric-noise inverse-variance fusion improving RMSE and variance versus the same single RAW;
- per-pixel occlusion rejection preventing a foreign measurement from ghosting into the reference grid;
- exposure mismatch narrowing to a reference-only product;
- censored-reference preservation without invented recovery;
- exact M5/M6 binding rejecting foreign alignment evidence;
- deterministic output/manifest/graph digests;
- resident-memory admission failure below the proven bound;
- 1× CFA product semantics without demosaic or remosaic.

CI success establishes the M7 software reference baseline only. **It does not establish exact-profile physical quality acceptance.** Physical M7 acceptance still requires the same captured scene evaluated against the same single RAW and prior accepted reference graph across stratified light, motion, texture, clipping, ISO/temperature, and occlusion conditions, with declared p50/p95/p99 quality and artifact gates.

## Tier-A review fields

**Invariant:** M7 reads immutable M5/M6 evidence only, never owns Camera2, never demosaics/remosaics, never renders, never changes the reference sampling grid, and never converts rejected measurements into valid support.

**Ownership transfer:** no Android image ownership enters M7; all camera/image ownership was released before immutable M4/M5 publication.

**Stale behavior:** M7 has no live session authority. It requires the M6 cryptographic measurement binding to match the supplied immutable M5 evidence.

**Failure classification:** reconstruction exclusion/fallback cannot mutate camera discovery, RAW support, calibration trust, active selection, or session state.

**CI guard impact:** `verify-m7-reconstruction.sh` runs after the M6 guard and before camera UI/native/build validation.

**Physical acceptance step:** collect exact-profile same-scene quality, ghost/false-detail, uncertainty calibration, memory, and runtime evidence. Do not claim device/profile computational-photo certification until those gates pass.

## Explicitly deferred

M7 does not implement M8B computational DNG, M9 optimization, M10 RAW-video acquisition, M13 HDR/night/super-resolution, or M14 AI. The dependency roadmap now permits M8B and M9 work after a green M7 software checkpoint, while M10 still also requires the M2A + M2B + M4 dependencies and physical evidence gates.
