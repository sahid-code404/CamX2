# ADR-037: Exact-profile calibration and noise are explicit measurement state

Status: Accepted for the M5 software reference baseline; exact-profile physical calibration evidence remains pending

## Context

M4 provides immutable padding-free RAW_SENSOR `FrameSet` evidence, but raw digital numbers are not yet calibrated measurements. Reconstruction cannot truthfully reason about clipping, radiometry, variance, or color interpretation if black level, white level, CFA/active area, noise, or color matrices are guessed or collapsed into one generic confidence score.

ADR-022 already requires calibration, noise, censoring, and uncertainty to remain first-class state.

## Decision

M5 introduces an exact-profile `M5CalibrationProfile` bound to one canonical lens fingerprint, one camera-profile fingerprint, RAW dimensions, CFA pattern, and active area.

Black and white levels are represented independently for all four 2x2 CFA sites. Each white level must be strictly above its corresponding black level. RAW_SENSOR reference decoding is explicit `LITTLE_ENDIAN_16`; M5 does not infer an undeclared packing or byte order.

The reference noise model is per CFA site:

`variance_DN2 = shotSlope_DN2_per_DN * signal_DN + readVariance_DN2 + (fpnFractionSigma * signal_DN)^2`

Shot, read, and fixed-pattern terms retain separate confidence dimensions. Black level, white level, CFA/active-area, and optional color calibration also retain separate confidence dimensions. No single generic confidence scalar replaces these meanings.

Color matrices are optional. When present they are explicitly bound to named/reference illuminants, finite, and non-singular. Missing matrix/illuminant evidence remains missing; M5 never fabricates a color transform.

`ReferenceCalibrationEngine` converts an immutable M4 `FrameSet` into an immutable calibrated measurement set without Camera2, ImageReader, UI, network, AI, or filesystem ownership. It preserves the source canonical SHA-256, keeps the original RAW16 evidence available by defensive copy, and exposes per-sample signal DN, normalized signal, variance, low/high censoring, active-area membership, CFA site, and CFA color.

A `CalibrationReservation` proves the input FrameSet plus one immutable calibrated RAW copy and a fixed safety margin before copying begins. Insufficient resident budget fails before partial publication.

## Validation gate

M5 defines deterministic dark, flat, and linearity corpus residual gates plus independent minimum confidence thresholds. The software evaluator reports dark RMSE in DN, flat normalized RMSE, worst per-site linearity normalized RMSE, and pass/fail state for each confidence dimension.

Passing synthetic/unit tests does not certify any physical camera profile. Exact-profile physical acceptance requires measured dark/flat/linearity corpus evidence and provenance outside CI.

## Consequences

M6 can consume explicit calibrated measurement/noise state without reinterpreting camera metadata. Calibration failure cannot be hidden as a reconstruction quality choice. AI is forbidden in M5, and later optimized providers may not redefine these reference semantics.
