# ADR-025: Split Sensor and Computational DNG contracts

Status: Accepted

## Context

Sensor CFA evidence and reconstructed CFA/Linear negatives require different metadata authority. Forcing both through one writer risks fabricated or stale Camera2 semantics.

## Decision

`SensorDngWriter` and `ComputationalDngWriter` are distinct contracts. Computational metadata is output-derived. `FusedCfaRadiance` may use truthful CFA DNG; `LinearSceneRgb` uses Linear DNG semantics with no CFA tags. Processed-source outputs never masquerade as RAW.

DNG validity and real decoder interoperability are separate gates; no metadata is invented to satisfy a reader.

## Consequences

The exact computational writer remains an M8B implementation choice and can fail without changing negative semantics.
