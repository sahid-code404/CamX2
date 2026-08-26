# ADR-018: Shared EvidenceConstrainedImagingEngine and independent user axes

Status: Accepted

## Context

Photo and video need the same measurement/calibration/alignment/reconstruction science but different temporal and delivery policies.

## Decision

CamX uses one `EvidenceConstrainedImagingEngine` for computational photo and video. Sensor modes bypass sample-changing computation. User intent is the independent product of `ReconstructionIntent`, `SourcePolicy`, `VideoExecution`, and `SourceRetention`.

An `AUTO` shortcut may choose recipes/providers but cannot silently change `SourcePolicy`.

## Consequences

Scientific fixes are shared across media modes while acquisition cadence, temporal windows, deadlines, persistence, and container policy remain orchestration concerns.
