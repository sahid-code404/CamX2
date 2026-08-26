# ADR-017: One canonical optical lens per reconstruction

Status: Accepted

## Context

Logical/physical routes and failover profiles can represent the same optics, while unrelated lenses require different calibration and geometry.

## Decision

One computational capture/reconstruction uses exactly one canonical optical lens and one compatible route/profile/calibration epoch. Source identity records the complete historical canonical lens, route/profile, physical target, sensor mode, and generations.

A future multi-camera product requires a separate Tier-A ADR and new product semantics; it is not an implicit extension of single-lens fusion.

## Consequences

Profile failover can preserve canonical identity, but frames from unrelated optics cannot be silently pooled to improve a burst.
