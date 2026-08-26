# ADR-022: Measurement, visibility, noise, and uncertainty are first-class state

Status: Accepted

## Context

Frames are noisy, clipped, moving measurements. A normalized bitmap plus a merge mask loses the information needed to reason about radiometry, occlusion, confidence, and false detail.

## Decision

Reconstruction uses explicit observation/calibration/noise models, motion/alignment evidence, visibility/occlusion/inlier support, and uncertainty. Computational outputs retain relevant variance, censoring, support, conditioning, calibration confidence, motion ambiguity, and learned-provider OOD/calibration state.

No single generic confidence scalar replaces these meanings.

## Consequences

Algorithms can lower confidence or fall back when evidence is absent/ambiguous instead of inventing detail. Manifests remain scientifically auditable.
