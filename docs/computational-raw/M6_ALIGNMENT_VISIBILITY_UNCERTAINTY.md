# M6 — Alignment, Visibility, Occlusion, and Uncertainty

Status: Software reference checkpoint; exact-profile real residual and catastrophic-percentile evidence remains a separate acceptance gate

M6 consumes M5 calibrated measurement frames and estimates which measurements may participate in M7 reconstruction. It does not reconstruct a computational negative, render an image, operate Camera2, or introduce AI.

## Implemented reference contracts

Production code lives in `core/imaging/alignment/`:

- `AlignmentRequest` freezes reference ordinal, even CFA-phase-preserving search radius, bounded sampling, support/residual/ambiguity gates, and minimum accepted subset size.
- `AlignmentReservation` proves the finite candidate grid and worst-case global + top-band + bottom-band score-evaluation count before alignment begins.
- `ReferenceAlignmentEngine` performs deterministic classical translation matching directly on calibrated mosaic samples. Candidate cost is the mean squared signal residual normalized by propagated per-sample variance; censored samples are not scored.
- `FrameSupportSummary` keeps geometric visibility, usable uncensored support, inlier support, and occluded support separate.
- `PixelMeasurementSupport` exposes visibility, censoring, inlier/occlusion state, normalized residual, propagated variance, and alignment uncertainty without allocating or fabricating a dense confidence mask.
- `RollingShutterMotionEvidence` independently estimates top- and bottom-band translation and reports their disagreement.
- `AlignmentUncertainty` exposes translation-separation, residual, and support-loss uncertainty dimensions rather than hiding them in one quality score.
- `AlignmentEvidenceSet` freezes every per-frame decision and a deterministic reconstruction subset. It falls back from full set -> smaller accepted subset -> reference-only.
- `AlignmentTruthEvaluator` reports accepted fraction, p50/p95/p99 synthetic translation error, and catastrophic-failure fraction.

## Reference algorithm truth boundary

The initial reference family is intentionally narrow: bounded even-integer translation only. Even displacement preserves the Bayer 2x2 phase, allowing calibrated mosaic-domain comparison without demosaicing. This implementation does not claim subpixel, affine, projective, optical-flow, depth, or learned motion support.

The narrow reference remains valuable because its semantics are deterministic and auditable. Future providers may improve the estimate but cannot redefine what visible, censored, inlier, occluded, or uncertain means.

## Failure and fallback

A non-reference frame is excluded when geometric visibility, uncensored usable support, inlier support, residual, or best-versus-second-best separation violates the request. Exclusion is not repaired with invented measurements. If the individually valid frames do not satisfy `minimumAcceptedFrames`, the reconstruction subset becomes the reference frame only.

The reference frame remains valid even when other frames are ambiguous. A flat/periodic texture with equal candidate costs is therefore a deterministic ambiguity failure, not an arbitrary tie-selected motion claim.

## Rolling-shutter and uncertainty evidence

Top and bottom active-area bands are independently searched with the same bounded candidate family. Their translation disagreement is explicit rolling-shutter/motion evidence. The current translation-sigma output is a deterministic separation-derived proxy; it is not claimed to be a calibrated posterior distribution. Residual sigma and support loss remain separate.

## Validation

Unit tests cover known synthetic even translation, deterministic repeatability, ambiguous-texture fallback, bad-frame subset fallback, per-pixel occlusion support, rolling-shutter band disagreement, exact reservation binding, bounded-search rejection, CFA-phase rejection, synthetic percentile success, and catastrophic exclusion accounting.

CI passing establishes the M6 software reference baseline only. **M6 does not claim exact-profile physical alignment acceptance from synthetic CI.** Real-device acceptance still requires stratified residuals for motion, rolling shutter, low texture, clipping, occlusion, exposure spread, and representative temperature/ISO conditions, with declared p95/p99 and catastrophic-failure gates.

## Tier-A review fields

**Invariant:** M6 consumes immutable M5 measurement evidence, never owns the camera, never demosaics/remosaics to make alignment easier, and never converts invalid measurements into valid support.

**Ownership transfer:** no Android image ownership enters M6; immutable M5 evidence is read only and M6 publishes immutable support/alignment records.

**Stale behavior:** M6 has no live session authority. Generation staleness is closed at M4 before immutable `FrameSet` publication.

**Failure classification:** alignment ambiguity/support/residual failure changes only the reconstruction subset. It cannot reclassify camera discovery, RAW support, or calibration validity.

**CI guard impact:** `verify-m6-alignment.sh` is inserted after M5 and before camera UI/native/build validation.

**Physical acceptance step:** collect exact-profile real residual distributions and catastrophic percentiles against declared motion/occlusion/rolling-shutter truth or trusted references; no support claim advances until those gates pass.

## Explicitly deferred

M6 does not implement M7 fusion/reconstruction. The next dependency-ordered milestone after a green M6 software checkpoint is **M7 computational photo reconstruction**.
