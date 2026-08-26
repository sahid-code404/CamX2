# M5 — Calibration and Noise Model

Status: Software reference checkpoint; exact-profile dark/flat/linearity physical evidence remains a separate acceptance gate

M5 turns the immutable M4 `FrameSet` into calibrated measurement frames while preserving sensor evidence and keeping calibration/noise confidence explicit. It does not implement M6 alignment/visibility, M7 reconstruction, HDR/night recipes, AI, GPU processing, or another camera owner.

## Implemented reference contracts

Production M5 code lives in `core/imaging/calibration/`:

- `M5CalibrationProfile` binds calibration to exact canonical-lens/profile fingerprints, RAW dimensions, active area, CFA pattern, black/white levels, per-site noise model, optional illuminant-bound color matrices, origin, and explicit RAW16 byte order.
- `CalibrationConfidenceVector` retains independent confidence for black level, white level, CFA/active area, shot noise, read noise, fixed-pattern noise, and optional color calibration. There is deliberately no generic scalar confidence.
- `NoiseParameters` uses an auditable shot + read + multiplicative fixed-pattern variance model in the RAW DN domain.
- `CalibrationReservation` proves resident memory for the original immutable FrameSet, one immutable calibrated RAW copy, and a safety margin before conversion begins.
- `CalibratedMeasurementFrame` retains the source canonical SHA-256 and exposes calibrated sample semantics: RAW DN, black/white levels, signal DN, normalized signal, variance, low/high censoring, active-area membership, CFA site, and CFA color.
- `ReferenceCalibrationEngine` is deterministic scalar/reference code. It validates exact profile identity and reservation before any frame copies are published.
- `CalibrationCorpusEvaluator` implements bounded deterministic dark, flat, and per-CFA-site linearity residual gates plus independent confidence gates.

## Truth boundaries

M5 does not guess missing calibration. Color matrices are absent when valid matrix/illuminant evidence is absent. `LITTLE_ENDIAN_16` is explicit in the software reference profile instead of being inferred from an opaque/private source. A white level that does not exceed black, a singular matrix, mismatched profile identity, malformed corpus, insufficient memory proof, or insufficient confidence fails closed.

The canonical RAW raster is not demosaiced, denoised, aligned, fused, sharpened, tone mapped, upscaled, or artistically rendered. M5 is measurement interpretation only.

## Noise semantics

For each CFA site, the reference model is:

`variance_DN2 = shotSlope * signal_DN + readVariance_DN2 + (fpnFractionSigma * signal_DN)^2`

High-clipped samples remain explicitly censored. Their variance evaluation is bounded at the usable white-minus-black range; the clipped value is not treated as a trustworthy radiometric measurement.

## Validation corpus

`CalibrationValidationCorpus` requires bounded dark, flat, and linearity evidence covering all four CFA sites. The evaluator reports:

- dark RMSE against per-site black level;
- flat normalized RMSE against unit response;
- worst per-site normalized linearity RMSE from a deterministic through-origin fit;
- pass/fail for black, white, geometry, shot, read, FPN, and optional color confidence independently.

CI exercises deterministic synthetic corpora and failure cases. This proves software semantics, not physical calibration quality.

## Tier-A review fields

**Invariant:** calibration is exact-profile, deterministic, immutable, downstream of M4, and never owns Camera2 resources.

**Ownership transfer:** immutable M4 canonical bytes are defensively copied only after a resident-memory proof; source digests must remain identical.

**Stale behavior:** M5 has no live camera authority. It consumes a completed immutable `FrameSet`; generation staleness is already resolved at the M4 transaction boundary.

**Failure classification:** calibration/profile/corpus/resource failures remain processing evidence failures. They cannot reclassify camera discovery, lens support, or capture integrity.

**Unit tests:** black/white ordering, confidence separation, matrix validity, deterministic profile digest, explicit little-endian decoding, censoring, exact identity rejection, resident-budget rejection, immutable copy, noise formula, synthetic residual acceptance, residual rejection, and independent confidence rejection.

**CI guard impact:** `verify-m5-calibration.sh` is inserted after M4 and before camera UI/native/build validation.

**Physical acceptance step:** for each exact profile, collect provenance-bound dark frames, flat fields, and linearity sweeps across representative ISO/exposure/temperature strata; fit/validate black, white, shot, read, and FPN terms; record residual distributions and confidence; only then certify that exact calibration profile.

## Explicitly deferred

M5 does not claim any exact physical profile is calibrated by CI. The next dependency-ordered scientific milestone after a green M5 software checkpoint is **M6 alignment, visibility, occlusion, and uncertainty**.
