# ADR-038: Classical alignment produces explicit visibility, occlusion, and uncertainty evidence

Status: Accepted for the M6 software reference baseline; exact-profile real residual acceptance remains pending

## Context

M5 produces immutable calibrated measurement frames with explicit noise and censoring semantics. M7 reconstruction must not treat every pixel in every frame as equally valid: motion, field-of-view loss, rolling-shutter disagreement, clipping, occlusion, and ambiguous texture can invalidate evidence.

ADR-022 requires visibility, noise, censoring, and uncertainty to remain first-class state. The M6 roadmap requires a classical reference, rolling-shutter/motion evidence, inlier/occlusion support, uncertainty propagation, synthetic truth, real residuals, catastrophic-failure percentile limits, and deterministic fallback.

## Decision

M6 introduces a deterministic scalar/reference alignment baseline downstream of `CalibratedMeasurementFrameSet`. It does not own Camera2, ImageReader, UI, storage, networking, GPU, or AI resources.

The first reference motion family is bounded integer translation in two-pixel increments. The even increment is deliberate: it keeps the 2x2 CFA phase invariant while comparing calibrated mosaic samples directly, without demosaicing or remosaicing. Subpixel, affine, optical-flow, and learned providers remain future replaceable estimators and may not redefine the reference evidence semantics.

Each candidate translation is scored from uncensored same-CFA calibrated samples using noise-normalized squared residuals. Search radius, sampling step, and total score evaluations are pre-admitted by `AlignmentReservation`. No unbounded search or full-resolution hidden work is permitted.

M6 publishes separate evidence for geometric visibility, usable uncensored support, inlier support, occluded support, motion ambiguity, residual magnitude, translation uncertainty, and top-versus-bottom rolling-shutter disagreement. Censored measurements never become inliers. Pixels that map outside the active area are invisible rather than synthesized.

A frame that violates visibility, usable-support, inlier, residual, or ambiguity gates is excluded. If enough individually accepted frames remain, reconstruction receives that deterministic smaller subset. Otherwise the output falls back to the reference frame only. The reference is always a truthful legal reconstruction input.

## Uncertainty semantics

The software baseline reports three explicit diagnostics: translation sigma proxy from best-versus-second-best separation, residual sigma from the selected noise-normalized cost, and support-loss fraction. These are reference uncertainty diagnostics, not yet physically calibrated probability distributions. Exact-profile physical validation must calibrate or replace their interpretation before any stronger statistical claim.

## Validation

`AlignmentTruthEvaluator` reports accepted fraction, p50/p95/p99 translation error over accepted synthetic truth cases, and a catastrophic fraction where exclusions or errors above the configured catastrophic threshold count as catastrophic. CI synthetic truth proves deterministic reference semantics only.

The milestone is not physically complete until exact-profile real bursts supply residual distributions, rolling-shutter/motion strata, low-texture and occlusion cases, and catastrophic-failure percentile evidence within declared gates.

## Consequences

M7 can consume an explicit accepted subset plus per-frame/per-pixel support instead of guessing which measurements are valid. An optimized or learned provider may improve motion estimation later, but failure must return to this deterministic classical/reference path and never invent visibility or detail.
