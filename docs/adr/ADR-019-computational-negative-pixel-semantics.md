# ADR-019: ComputationalNegative pixel semantics

Status: Accepted

## Context

Multi-frame reconstruction may either preserve a genuine CFA grid or produce full-color/geometry-changed samples. Remosaicing the latter would manufacture Bayer semantics.

## Decision

`ComputationalNegative` has two legal pixel variants. `FusedCfaRadiance` is restricted to a genuine 1× CFA grid with one physical sensor/mode/color basis and no joint demosaic, SR, or geometry reconstruction. `LinearSceneRgb` is mandatory after full-color, SR, or geometry-changing reconstruction.

`LinearSceneRgb` is never remosaiced merely to appear RAW.

## Consequences

DNG/export decisions follow actual pixel semantics rather than desired file branding.
