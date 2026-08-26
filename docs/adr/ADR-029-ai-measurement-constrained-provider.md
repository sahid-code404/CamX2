# ADR-029: AI is an optional measurement-constrained provider

Status: Accepted

## Context

Learned models can improve estimation or priors but can also hallucinate detail, hide uncertainty, and create an unportable single-provider dependency.

## Decision

AI may estimate scene/noise/motion/visibility/quality/OOD state or provide a declared regularizer within the frozen measurement/resource contract. It may never alter Sensor-mode samples, relabel sources/products, change canonical identity, fabricate calibration, erase clipping/occlusion, claim prior detail as measured resolution, bypass bounds, or become the only required implementation.

Models/providers are immutable version/hash-addressed job leases with calibrated OOD/confidence and deterministic classical fallback.

## Consequences

AI is deferred to M14 after classical baselines and remains removable/quarantinable without changing file truth.
